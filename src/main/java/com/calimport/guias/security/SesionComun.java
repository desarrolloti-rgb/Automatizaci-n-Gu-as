package com.calimport.guias.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sesión común de apps.calimport.cl para quien usa Guías y el Panel de Facturadores (hoy,
 * solo el jefe de bodega). Es una cookie HttpOnly firmada con un secreto que comparten las dos
 * apps ({@code SESION_COMUN_SECRETO}). No abre nada por sí sola: cada app la canjea por su
 * propia sesión corta después de revisar en SAP que el empleado siga activo, con el cargo y
 * con la misma clave (ver {@link com.calimport.guias.controller.SesionComunController}).
 *
 * <p>El formato lo lee también el panel ({@code Panel-Facturadores/backend/src/sesionComun.js}):
 * si cambia acá, cambia allá, y las pruebas de los dos lados comparten un vector fijo.
 * <pre>base64url(json) + "." + base64url(HMAC-SHA256 de lo anterior)
 * json = {"v":1,"email":…,"employeeId":…,"sello":…,"exp":…}   (exp en segundos)</pre>
 *
 * <p>El sello es un HMAC de la clave guardada en SAP: cambiarla en SAP corta la sesión en todos
 * los equipos sin guardar sesiones en ninguna parte. La cookie no lleva permisos.
 *
 * <p>Sin secreto configurado queda apagada: no canjea ni borra nada. Así están el perfil local
 * y la instancia de prueba, que comparte dominio con producción y recibe su cookie.
 */
@Component
public class SesionComun {

    /** Lo que trae una cookie bien firmada y vigente. */
    public record Datos(String email, int employeeId, String sello, long exp) {
    }

    private static final int VERSION = 1;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_LECTOR = Base64.getUrlDecoder();

    private final SecretKeySpec clave;
    private final String nombreCookie;
    private final Duration duracion;
    private final boolean cookieSegura;
    private final ObjectMapper mapper = new ObjectMapper();

    public SesionComun(@Value("${sesion-comun.secreto:}") String secreto,
                       @Value("${sesion-comun.cookie:calimport_sesion}") String nombreCookie,
                       @Value("${sesion-comun.dias:30}") int dias,
                       @Value("${sesion-comun.cookie-segura:true}") boolean cookieSegura) {
        String limpio = secreto == null ? "" : secreto.trim();
        if (!limpio.isEmpty() && limpio.length() < 32) {
            // El panel exige el mismo mínimo: con uno corto, alguna de las dos no arrancaría.
            throw new IllegalArgumentException("SESION_COMUN_SECRETO debe tener al menos 32 caracteres");
        }
        this.clave = limpio.isEmpty() ? null : new SecretKeySpec(limpio.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.nombreCookie = nombreCookie;
        this.duracion = Duration.ofDays(dias);
        this.cookieSegura = cookieSegura;
    }

    public boolean activa() {
        return clave != null;
    }

    /** {@code claveEnSap} es la guardada en SAP (U_Password), no la que se escribió. */
    public String emitir(String email, int employeeId, String claveEnSap) {
        return emitir(email, employeeId, claveEnSap, Instant.now());
    }

    String emitir(String email, int employeeId, String claveEnSap, Instant ahora) {
        // Mismo orden de campos que el panel: el vector de prueba sale idéntico en los dos.
        ObjectNode json = mapper.createObjectNode();
        json.put("v", VERSION);
        json.put("email", email);
        json.put("employeeId", employeeId);
        json.put("sello", sello(claveEnSap));
        json.put("exp", ahora.plus(duracion).getEpochSecond());
        String carga = B64.encodeToString(mapper.writeValueAsBytes(json));
        return carga + "." + B64.encodeToString(hmac(carga));
    }

    public Optional<Datos> verificar(String valor) {
        return verificar(valor, Instant.now());
    }

    Optional<Datos> verificar(String valor, Instant ahora) {
        if (!activa() || valor == null) return Optional.empty();

        String[] partes = valor.split("\\.", -1);
        if (partes.length != 2 || partes[0].isEmpty() || partes[1].isEmpty()) return Optional.empty();
        String esperada = B64.encodeToString(hmac(partes[0]));
        if (!MessageDigest.isEqual(esperada.getBytes(StandardCharsets.US_ASCII),
                partes[1].getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }

        JsonNode json;
        try {
            json = mapper.readTree(B64_LECTOR.decode(partes[0]));
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
        String email = json.path("email").asText("");
        int employeeId = json.path("employeeId").asInt(0);
        String sello = json.path("sello").asText("");
        long exp = json.path("exp").asLong(0);
        if (json.path("v").asInt(0) != VERSION || email.isBlank() || employeeId <= 0 || sello.isBlank()
                || exp <= ahora.getEpochSecond()) {
            return Optional.empty();
        }
        return Optional.of(new Datos(email, employeeId, sello, exp));
    }

    /** Si la clave guardada hoy en SAP es la misma con la que se emitió la cookie. */
    public boolean selloCoincide(Datos datos, String claveEnSap) {
        return MessageDigest.isEqual(sello(claveEnSap).getBytes(StandardCharsets.US_ASCII),
                datos.sello().getBytes(StandardCharsets.US_ASCII));
    }

    /** Sin el secreto no se puede volver a la clave. Recortada igual que la lee el panel. */
    String sello(String claveEnSap) {
        String texto = "clave:" + (claveEnSap == null ? "" : claveEnSap.trim());
        return B64.encodeToString(Arrays.copyOf(hmac(texto), 16));
    }

    public Optional<String> leer(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> nombreCookie.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isEmpty())
                .findFirst();
    }

    /**
     * Path=/ a propósito: tiene que llegar al panel (/logistica-comercial/) y al ingreso común
     * (/login), no solo a /gd/. HttpOnly: la página no la lee, así que un XSS en cualquiera de
     * las dos apps se lleva a lo más el token de 12 h, no la sesión de 30 días.
     */
    public ResponseCookie cookie(String valor) {
        return construir(valor, duracion);
    }

    public ResponseCookie borrar() {
        return construir("", Duration.ZERO);
    }

    private ResponseCookie construir(String valor, Duration maxAge) {
        return ResponseCookie.from(nombreCookie, valor)
                .httpOnly(true)
                .secure(cookieSegura)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private byte[] hmac(String texto) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(clave);
            return mac.doFinal(texto.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }
}
