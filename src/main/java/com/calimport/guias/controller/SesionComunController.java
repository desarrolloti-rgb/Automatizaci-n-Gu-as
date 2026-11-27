package com.calimport.guias.controller;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calimport.guias.controller.dto.LoginResponse;
import com.calimport.guias.model.Rol;
import com.calimport.guias.sap.EmpleadoSap;
import com.calimport.guias.sap.SapClient;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.security.SesionComun;
import com.calimport.guias.security.SesionComun.Datos;
import com.calimport.guias.service.RepartidorService;
import com.calimport.guias.utils.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

/**
 * Canje de la sesión común ({@link SesionComun}) por el token de Guías de siempre, sin escribir
 * la clave. Lo usa el frontend al abrir una pestaña sin token y cuando el token vence, y el
 * ingreso común para entrar a las dos apps de una vez.
 *
 * <p>Cada canje vuelve a preguntarle a SAP: activo (la consulta ya filtra {@code Active}), mismo
 * EmployeeID, misma clave y cargo de jefe de bodega. Si algo de eso cambió, la sesión común se
 * borra y queda el 401 de siempre. Si SAP no responde es un 502 y la cookie se conserva: una
 * caída de SAP no puede obligar a volver a entrar.
 *
 * <p>Vive aparte de {@link AuthController} porque tiene que existir también con
 * {@code auth.modo=local}, donde sin secreto simplemente responde 401.
 */
@RestController
@RequestMapping("/api/auth")
public class SesionComunController {

    private static final Logger log = LoggerFactory.getLogger(SesionComunController.class);

    private final SesionComun sesionComun;
    private final SapClient sapClient;
    private final RepartidorService repartidorService;
    private final JwtTokenProvider jwtTokenProvider;

    public SesionComunController(SesionComun sesionComun, SapClient sapClient,
                                 RepartidorService repartidorService, JwtTokenProvider jwtTokenProvider) {
        this.sesionComun = sesionComun;
        this.sapClient = sapClient;
        this.repartidorService = repartidorService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/renovar")
    public ResponseEntity<?> renovar(HttpServletRequest request) {
        // Apagada (local, instancia de prueba): ni se lee ni se borra la cookie, que en la de
        // prueba es la de producción.
        if (!sesionComun.activa()) return sinSesion(false);

        Optional<String> valor = sesionComun.leer(request);
        if (valor.isEmpty()) return sinSesion(false);

        Optional<Datos> leida = sesionComun.verificar(valor.get());
        if (leida.isEmpty()) {
            // Vencida es raro (el navegador la descarta al cumplir su Max-Age). Si esto se repite,
            // lo más probable es que SESION_COMUN_SECRETO no sea el mismo que en el panel.
            log.warn("Sesión común rechazada: firma inválida o vencida. Si se repite, revisar que "
                    + "SESION_COMUN_SECRETO sea el mismo en Guías y en el panel.");
            return sinSesion(true);
        }
        Datos datos = leida.get();

        JsonNode empleados;
        try {
            empleados = sapClient.queryEmployeeByEmail(datos.email());
        } catch (Exception e) {
            log.error("No se pudo renovar la sesión común: SAP no respondió ({}).", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error de conexión con SAP");
        }

        JsonNode lista = empleados == null ? null : empleados.get("value");
        JsonNode empleado = lista != null && lista.isArray() && !lista.isEmpty() ? lista.get(0) : null;
        if (empleado == null || empleado.path("EmployeeID").asInt(0) != datos.employeeId()) {
            log.info("Sesión común de {} cerrada: ya no está activo en SAP.", datos.email());
            return sinSesion(true);
        }
        String claveEnSap = empleado.path("U_Password").asText("");
        if (!sesionComun.selloCoincide(datos, claveEnSap)) {
            log.info("Sesión común de {} cerrada: cambió su clave en SAP.", datos.email());
            return sinSesion(true);
        }
        String cargo = empleado.path("JobTitle").asText("").trim();
        if (EmpleadoSap.rolDeCargo(cargo).orElse(null) != Rol.JEFE_BODEGA) {
            log.info("Sesión común de {} cerrada: su cargo (\"{}\") ya no es jefe de bodega.", datos.email(), cargo);
            return sinSesion(true);
        }

        String email = empleado.path("eMail").asText(datos.email());
        String nombre = EmpleadoSap.nombreCompleto(empleado);
        repartidorService.upsertDesdeSap(datos.employeeId(), nombre, email, true, Rol.JEFE_BODEGA);
        String token = jwtTokenProvider.generateToken(email, datos.employeeId(), nombre, Rol.JEFE_BODEGA);

        // Se vuelve a emitir: los 30 días son sin uso, cada canje empieza a contar de nuevo.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sesionComun.cookie(sesionComun.emitir(email, datos.employeeId(), claveEnSap)).toString())
                .body(new LoginResponse(token));
    }

    /** "Salir" en Guías cierra también la sesión común, y con ella la entrada al panel. */
    @PostMapping("/salir")
    public ResponseEntity<Void> salir() {
        if (!sesionComun.activa()) return ResponseEntity.noContent().build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sesionComun.borrar().toString()).build();
    }

    private ResponseEntity<ProblemDetail> sinSesion(boolean borrarCookie) {
        ResponseEntity.BodyBuilder respuesta = ResponseEntity.status(HttpStatus.UNAUTHORIZED);
        if (borrarCookie) {
            respuesta.header(HttpHeaders.SET_COOKIE, sesionComun.borrar().toString());
        }
        return respuesta.body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Sesión no iniciada."));
    }
}
