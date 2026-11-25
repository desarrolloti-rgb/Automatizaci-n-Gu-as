package com.calimport.guias.controller;

import com.calimport.guias.controller.dto.LoginRequest;
import com.calimport.guias.controller.dto.LoginResponse;
import com.calimport.guias.model.Rol;
import com.calimport.guias.sap.SapClient;
import com.calimport.guias.sap.SapSessionManager.SapUnauthorizedException;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.security.LimitadorLogin;
import com.calimport.guias.security.SesionComun;
import com.calimport.guias.service.RepartidorService;
import com.calimport.guias.utils.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login de repartidores: valida contra SAP EmployeesInfo (mismo mecanismo que el portal
 * de proveedores en Dashboard) y, si es correcto, sincroniza la copia local en
 * {@link com.calimport.guias.model.Repartidor} y emite el JWT.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "auth.modo", havingValue = "sap", matchIfMissing = true)
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Mismo texto que el Panel de Facturadores, exista o no el correo. */
    static final String CREDENCIALES_INVALIDAS = "Correo o contraseña incorrectos.";

    private final SapClient sapClient;
    private final RepartidorService repartidorService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final LimitadorLogin limitadorLogin;
    private final SesionComun sesionComun;

    public AuthController(SapClient sapClient, RepartidorService repartidorService,
                           PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider,
                           LimitadorLogin limitadorLogin, SesionComun sesionComun) {
        this.sapClient = sapClient;
        this.repartidorService = repartidorService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.limitadorLogin = limitadorLogin;
        this.sesionComun = sesionComun;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody @Valid LoginRequest request, HttpServletRequest http,
                               HttpServletResponse response) {
        // Antes de consultar SAP: una IP o una cuenta bloqueada no debe generar trabajo.
        String ip = ipCliente(http);
        long bloqueo = Math.max(limitadorLogin.bloqueoIp(ip), limitadorLogin.bloqueoCorreo(request.email()));
        if (bloqueo > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos fallidos. Vuelva a intentar en " + Math.ceilDiv(bloqueo, 60_000) + " minuto(s).");
        }

        JsonNode employees;
        try {
            employees = sapClient.queryEmployeeByEmail(request.email());
        } catch (SapUnauthorizedException e) {
            log.error("SAP session error: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error de sesión con SAP");
        } catch (Exception e) {
            log.error("Login error: {}", e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error de conexión con SAP");
        }

        JsonNode value = employees.get("value");
        JsonNode employee = value != null && value.isArray() && !value.isEmpty() ? value.get(0) : null;
        String storedPassword = employee != null && employee.has("U_Password") ? employee.get("U_Password").asText() : "";

        // La clave se compara siempre, exista o no el correo, y el mensaje es el mismo en los
        // dos casos: ni la respuesta ni su tiempo revelan qué correos están cargados en SAP.
        boolean claveCorrecta = isValidPassword(request.password(), storedPassword);
        if (employee == null || !claveCorrecta) {
            limitadorLogin.registrarFallo(ip, request.email());
            log.warn("Login fallido desde {}.", ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, CREDENCIALES_INVALIDAS);
        }
        limitadorLogin.registrarExito(request.email());

        if (!employee.has("EmployeeID") || !employee.has("eMail")) {
            log.error("Respuesta de SAP sin EmployeeID o eMail: {}", employee);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Respuesta inesperada de SAP");
        }
        int employeeId = employee.get("EmployeeID").asInt();
        String email = employee.get("eMail").asText();
        String nombre = nombreCompleto(employee);

        // Después de validar la clave: este mensaje solo lo ve quien tiene credenciales
        // correctas, así el login no revela qué correos existen.
        String cargo = employee.has("JobTitle") ? employee.get("JobTitle").asText("").trim() : "";
        Rol rol = rolDeCargo(cargo).orElseThrow(() -> {
            log.info("Ingreso a Guías rechazado para {}: el cargo \"{}\" no tiene acceso.", email,
                    cargo.isEmpty() ? "sin cargo" : cargo);
            return new ApiException(HttpStatus.FORBIDDEN, "Su cargo" + (cargo.isEmpty() ? "" : " (" + cargo + ")")
                    + " no tiene acceso a Guías. Si debería tenerlo, consulte con TI.");
        });

        repartidorService.upsertDesdeSap(employeeId, nombre, email, true, rol);

        String token = jwtTokenProvider.generateToken(email, employeeId, nombre, rol);

        // El jefe de bodega usa también el Panel de Facturadores: queda con la sesión común
        // (SesionComun), que le abre las dos apps sin volver a escribir la clave. En el
        // ingreso común es el panel quien la emite; acá la recibe si entra por el formulario
        // propio (app instalada, desarrollo). A cualquier otro se le borra la que hubiera: si
        // el jefe no salió en este navegador, su cookie seguiría ahí y, al vencer el token de
        // quien entró ahora, el canje lo dejaría adentro como jefe.
        if (sesionComun.activa()) {
            response.addHeader(HttpHeaders.SET_COOKIE, rol == Rol.JEFE_BODEGA
                    ? sesionComun.cookie(sesionComun.emitir(email, employeeId, storedPassword)).toString()
                    : sesionComun.borrar().toString());
        }
        return new LoginResponse(token);
    }

    /**
     * Solo los cargos de bodega entran a Guías. Desde el 2026-11-09 el jefe de bodega entra
     * además al Panel de Facturadores, con una sesión común a las dos ({@link SesionComun});
     * el resto de los cargos sigue entrando a una sola app desde el ingreso común
     * (https://apps.calimport.cl/login, decisión del 2026-10-06). El panel reconoce al jefe
     * con esta misma regla (Panel-Facturadores/backend/src/auth.js): si cambia acá, cambia
     * allá. La comparación ignora mayúsculas, tildes y espacios de más.
     * <ul>
     *   <li>JEFE_BODEGA: "Jefe Bodega" y sus variantes, incluido el typo real "Jefe bogeda".
     *       Desde el 2026-11-25 también "Gerencia Operaciones", que ve todas las pestañas del
     *       portal (panel y Guías) con la misma sesión común.</li>
     *   <li>Despachador: "Bodeguero"/"Bodeguera" con o sin sucursal ("Bodeguero CCP",
     *       "Bodeguera ANT"), "Despachador"/"Despachadora", "Bodega" y "Personal bodega".</li>
     * </ul>
     * Cualquier otro cargo, vacío o ausente no entra: un cargo inesperado no gana acceso
     * por accidente.
     */
    static Optional<Rol> rolDeCargo(String jobTitle) {
        String cargo = Normalizer.normalize(jobTitle == null ? "" : jobTitle, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");

        if ((cargo.startsWith("jefe") && (cargo.contains("bodega") || cargo.contains("bogeda")))
                || cargo.equals("gerencia operaciones")) {
            return Optional.of(Rol.JEFE_BODEGA);
        }
        if (cargo.startsWith("bodeguer") || cargo.startsWith("despachador") || cargo.equals("bodega")
                || cargo.equals("personal bodega")) {
            return Optional.of(Rol.Despachador);
        }
        return Optional.empty();
    }

    /**
     * OJO: nombres de campo asumidos ("FirstName"/"LastName") por analogía con el resto
     * de EmployeesInfo en Dashboard (JobTitle, WorkBlock, Active). No están confirmados
     * contra el metadata real del Service Layer — verificar antes de confiar en el dato.
     */
    static String nombreCompleto(JsonNode employee) {
        String nombres = employee.has("FirstName") ? employee.get("FirstName").asText("") : "";
        String apellidos = employee.has("LastName") ? employee.get("LastName").asText("") : "";
        return (nombres + " " + apellidos).trim();
    }

    /**
     * Login dual: soporta hashes BCrypt ($2a$, $2b$, $2y$) y texto plano, igual que Dashboard.
     * Sin clave guardada (o sin empleado) igual se hace una comparación, para que ese camino
     * tarde lo mismo que una clave equivocada.
     */
    private boolean isValidPassword(String rawPassword, String storedPassword) {
        if (storedPassword == null || storedPassword.isEmpty()) {
            igualSeguro(rawPassword, "");
            return false;
        }
        if (storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$")
                || storedPassword.startsWith("$2y$")) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        return igualSeguro(rawPassword, storedPassword);
    }

    /**
     * Compara en tiempo constante: String.equals corta en el primer carácter distinto y el
     * tiempo de respuesta dejaría adivinar la clave de a un carácter. Se comparan los SHA-256
     * para que tampoco influya el largo.
     */
    private static boolean igualSeguro(String a, String b) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] izquierda = sha.digest(String.valueOf(a).getBytes(StandardCharsets.UTF_8));
            byte[] derecha = sha.digest(String.valueOf(b).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(izquierda, derecha);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /**
     * IP del cliente. Spring escucha solo en 127.0.0.1 (deploy/guias.service): si la conexión
     * viene de ahí es nginx, y la IP real es la última de X-Forwarded-For, la que agregó él.
     * Desde cualquier otro origen la cabecera la escribe el cliente y no se cree.
     */
    static String ipCliente(HttpServletRequest http) {
        String remota = http.getRemoteAddr();
        boolean desdeProxy = "127.0.0.1".equals(remota) || "0:0:0:0:0:0:0:1".equals(remota) || "::1".equals(remota);
        String cadena = http.getHeader("X-Forwarded-For");
        if (desdeProxy && cadena != null && !cadena.isBlank()) {
            String[] partes = cadena.split(",");
            String ultima = partes[partes.length - 1].trim();
            if (!ultima.isEmpty()) return ultima;
        }
        return remota == null ? "desconocida" : remota;
    }
}
