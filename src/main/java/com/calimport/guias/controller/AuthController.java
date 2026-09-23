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
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AuthController(SapClient sapClient, RepartidorService repartidorService,
                           PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
        this.sapClient = sapClient;
        this.repartidorService = repartidorService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
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

        Rol rol = rolDeJobTitle(employee);

        repartidorService.upsertDesdeSap(employeeId, nombre, email, true, rol);

        String token = jwtTokenProvider.generateToken(email, employeeId, nombre, rol);
        return new LoginResponse(token);
    }

    /**
     * El cargo de SAP decide el rol: si {@code JobTitle} empieza con "jefe" (sin importar
     * mayúsculas ni espacios) es JEFE_BODEGA — así cubre los typos reales como
     * "Jefe bogeda". Cualquier otro cargo, vacío o ausente queda como Despachador, el de
     * menos permisos: alguien con un cargo inesperado no gana poder por accidente.
     */
    private static Rol rolDeJobTitle(JsonNode employee) {
        String jobTitle = employee.has("JobTitle") ? employee.get("JobTitle").asText("") : "";
        return jobTitle.trim().toLowerCase(Locale.ROOT).startsWith("jefe")
                ? Rol.JEFE_BODEGA
                : Rol.Despachador;
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
