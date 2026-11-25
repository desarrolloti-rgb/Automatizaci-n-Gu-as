package com.calimport.guias.security;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qué ID token de Google abre /api/integraciones. La firma y el vencimiento los revisa el
 * decodificador; esto es lo que decide si el token es de quien tiene que ser.
 */
class ValidadorTokenIntegracionTest {

    private static final String AUDIENCIA = "https://apps.calimport.cl/gd";
    private static final String CUENTA = "samex-runner@charming-hearth-503417-i0.iam.gserviceaccount.com";

    private final ValidadorTokenIntegracion validador = new ValidadorTokenIntegracion(AUDIENCIA, List.of(CUENTA));

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer("https://accounts.google.com")
                .audience(List.of(AUDIENCIA))
                .claim("email", CUENTA)
                .claim("email_verified", true)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
    }

    private boolean acepta(Jwt jwt) {
        return !validador.validate(jwt).hasErrors();
    }

    @Test
    void aceptaElTokenDeLaCuentaDelLectorParaEstaAudiencia() {
        assertTrue(acepta(token().build()));
    }

    @Test
    void googleTambienEmiteSinElEsquemaEnElEmisor() {
        assertTrue(acepta(token().claim("iss", "accounts.google.com").build()));
    }

    @Test
    void laCuentaSeComparaSinMayusculas() {
        assertTrue(acepta(token().claim("email", CUENTA.toUpperCase()).build()));
    }

    @Test
    void rechazaUnTokenQueNoEmitioGoogle() {
        assertFalse(acepta(token().claim("iss", "https://otro.example.com").build()));
    }

    @Test
    void rechazaUnTokenParaOtraAudiencia() {
        // El de la instancia de prueba no tiene que valer en producción, y al revés.
        assertFalse(acepta(token().audience(List.of("https://apps.calimport.cl/gd-prueba")).build()));
    }

    @Test
    void rechazaOtraCuentaDeGoogle() {
        assertFalse(acepta(token().claim("email", "alguien@gmail.com").build()));
    }

    @Test
    void rechazaUnTokenSinCorreo() {
        // El servidor de metadatos lo omite si no se pide format=full.
        assertFalse(acepta(token().claims(c -> c.remove("email")).build()));
    }

    @Test
    void rechazaUnCorreoNoVerificado() {
        assertFalse(acepta(token().claim("email_verified", false).build()));
    }

    @Test
    void sinAudienciaOSinCuentasConfiguradasNoAceptaNada() {
        // Es el valor por defecto: hasta que se configure, /api/integraciones no abre a nadie.
        Jwt valido = token().build();

        assertTrue(new ValidadorTokenIntegracion("", List.of(CUENTA)).validate(valido).hasErrors());
        assertTrue(new ValidadorTokenIntegracion(AUDIENCIA, List.of()).validate(valido).hasErrors());
        assertTrue(new ValidadorTokenIntegracion(AUDIENCIA, List.of(" ")).validate(valido).hasErrors());
    }
}
