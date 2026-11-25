package com.calimport.guias.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import com.calimport.guias.security.ValidadorTokenIntegracion;

/**
 * La puerta de /api/integraciones: la usa el lector del portal de Samex, un Cloud Run Job sin
 * usuario en la app, que se identifica con el ID token de Google de su cuenta de servicio.
 *
 * <p>Es una cadena aparte de la de {@link SecurityConfig}, con prioridad para esas rutas: ahí no
 * vale el JWT de la app (el de un jefe de bodega da 401) y el token del lector solo da el rol
 * {@code INTEGRACION}, que no abre ningún otro endpoint; en el resto de la API, su token es uno
 * que la app no firmó y da 401. No hay secreto que guardar ni rotar: el token lo firma Google, dura
 * una hora, y {@link ValidadorTokenIntegracion} revisa para quién y de quién es.
 */
@Configuration
public class IntegracionesSecurityConfig {

    /** Las llaves públicas con que Google firma sus ID tokens. */
    static final String LLAVES_GOOGLE = "https://www.googleapis.com/oauth2/v3/certs";

    @Bean
    @Order(1)
    public SecurityFilterChain integracionesFilterChain(HttpSecurity http, JwtDecoder decodificadorIntegraciones)
            throws Exception {
        http
            .securityMatcher("/api/integraciones/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INTEGRACION"))
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                    .decoder(decodificadorIntegraciones)
                    .jwtAuthenticationConverter(IntegracionesSecurityConfig::comoIntegracion)));
        return http.build();
    }

    /**
     * Baja las llaves de Google la primera vez que llega un token (no al arrancar) y las guarda en
     * caché.
     */
    @Bean
    public JwtDecoder decodificadorIntegraciones(@Value("${guias.integraciones.audiencia:}") String audiencia,
                                                 @Value("${guias.integraciones.cuentas:}") List<String> cuentas) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(LLAVES_GOOGLE).build();
        decoder.setJwtValidator(validador(audiencia, cuentas));
        return decoder;
    }

    /** Vencimiento (con un minuto de tolerancia) y quién: lo mismo en producción y en los tests. */
    public static OAuth2TokenValidator<Jwt> validador(String audiencia, List<String> cuentas) {
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
                new ValidadorTokenIntegracion(audiencia, cuentas));
    }

    private static AbstractAuthenticationToken comoIntegracion(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_INTEGRACION")),
                jwt.getClaimAsString("email"));
    }
}
