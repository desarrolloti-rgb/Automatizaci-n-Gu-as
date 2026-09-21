package com.calimport.guias.controller;

import com.calimport.guias.controller.dto.LoginRequest;
import com.calimport.guias.controller.dto.LoginResponse;
import com.calimport.guias.model.Rol;
import com.calimport.guias.sap.SapClient;
import com.calimport.guias.sap.SapSessionManager.SapUnauthorizedException;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.service.RepartidorService;
import com.calimport.guias.utils.ApiException;
import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    private final SapClient sapClient;
    private final RepartidorService repartidorService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final Set<String> emailsJefesBodega;

    /**
     * @param jefesBodega emails separados por coma que entran como JEFE_BODEGA. Es
     *     <b>provisorio</b>: el rol debería salir de un campo de EmployeesInfo, pero todavía
     *     no está confirmado cuál es en esta instalación y no se inventa. Mientras tanto,
     *     sin esta lista nadie podría sincronizar ni asignar guías con login SAP.
     */
    public AuthController(SapClient sapClient, RepartidorService repartidorService,
                           PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider,
                           @Value("${auth.jefes-bodega:}") String jefesBodega) {
        this.sapClient = sapClient;
        this.repartidorService = repartidorService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.emailsJefesBodega = Arrays.stream(jefesBodega.split(","))
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody @Valid LoginRequest request) {
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
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No existe un usuario con ese email");
        }

        JsonNode employee = value.get(0);
        String storedPassword = employee.has("U_Password") ? employee.get("U_Password").asText() : "";
        if (!isValidPassword(request.password(), storedPassword)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Contraseña incorrecta");
        }

        if (!employee.has("EmployeeID") || !employee.has("eMail")) {
            log.error("Respuesta de SAP sin EmployeeID o eMail: {}", employee);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Respuesta inesperada de SAP");
        }
        int employeeId = employee.get("EmployeeID").asInt();
        String email = employee.get("eMail").asText();
        String nombre = nombreCompleto(employee);

        Rol rol = emailsJefesBodega.contains(email.trim().toLowerCase(Locale.ROOT)) ? Rol.JEFE_BODEGA : Rol.REPARTIDOR;

        repartidorService.upsertDesdeSap(employeeId, nombre, email, true, rol);

        String token = jwtTokenProvider.generateToken(email, employeeId, nombre, rol);
        return new LoginResponse(token);
    }

    /**
     * OJO: nombres de campo asumidos ("FirstName"/"LastName") por analogía con el resto
     * de EmployeesInfo en Dashboard (JobTitle, WorkBlock, Active). No están confirmados
     * contra el metadata real del Service Layer — verificar antes de confiar en el dato.
     */
    private static String nombreCompleto(JsonNode employee) {
        String nombres = employee.has("FirstName") ? employee.get("FirstName").asText("") : "";
        String apellidos = employee.has("LastName") ? employee.get("LastName").asText("") : "";
        return (nombres + " " + apellidos).trim();
    }

    /** Login dual: soporta hashes BCrypt ($2a$, $2b$, $2y$) y texto plano, igual que Dashboard. */
    private boolean isValidPassword(String rawPassword, String storedPassword) {
        if (storedPassword == null || storedPassword.isEmpty()) {
            return false;
        }
        if (storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$")
                || storedPassword.startsWith("$2y$")) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        return rawPassword.equals(storedPassword);
    }
}
