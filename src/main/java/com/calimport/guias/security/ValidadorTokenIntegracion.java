package com.calimport.guias.security;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Decide si un ID token de Google es de quien puede usar /api/integraciones: el lector de Samex,
 * que corre en Cloud Run con su propia cuenta de servicio. La firma (llaves públicas de Google) y
 * el vencimiento los revisa el decodificador; esto revisa a quién y para quién se emitió:
 *
 * <ul>
 *   <li>Lo emitió Google ({@code iss}).
 *   <li>Es para esta app ({@code aud}): producción y la instancia de prueba usan audiencias
 *       distintas, así un token no vale en la otra, igual que el {@code JWT_SECRET}.
 *   <li>Es de una de las cuentas autorizadas, con el correo verificado. El servidor de metadatos
 *       de Cloud Run solo pone el correo si se pide {@code format=full}.
 * </ul>
 *
 * <p>Sin audiencia o sin cuentas configuradas no acepta ningún token: es el valor por defecto, así
 * /api/integraciones queda cerrada hasta que alguien la configure a propósito.
 */
public final class ValidadorTokenIntegracion implements OAuth2TokenValidator<Jwt> {

    // Google firma sus ID tokens con uno u otro: con y sin esquema.
    private static final Set<String> EMISORES_GOOGLE = Set.of("https://accounts.google.com", "accounts.google.com");

    private final String audiencia;
    private final Set<String> cuentas;

    public ValidadorTokenIntegracion(String audiencia, Collection<String> cuentas) {
        this.audiencia = audiencia == null ? "" : audiencia.strip();
        this.cuentas = cuentas.stream()
                .map(c -> c == null ? "" : c.strip().toLowerCase(Locale.ROOT))
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (audiencia.isEmpty() || cuentas.isEmpty()) {
            return falla("Las integraciones no están configuradas (GUIAS_INTEGRACIONES_AUDIENCIA y GUIAS_INTEGRACIONES_CUENTAS)");
        }
        // Como texto y no con getIssuer(): "accounts.google.com" no es una URL.
        if (!EMISORES_GOOGLE.contains(jwt.getClaimAsString("iss"))) {
            return falla("El token no lo emitió Google");
        }
        if (jwt.getAudience() == null || !jwt.getAudience().contains(audiencia)) {
            return falla("El token es para otra audiencia");
        }
        String correo = jwt.getClaimAsString("email");
        if (correo == null || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))
                || !cuentas.contains(correo.toLowerCase(Locale.ROOT))) {
            return falla("La cuenta del token no está autorizada para las integraciones");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult falla(String descripcion) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", descripcion, null));
    }
}
