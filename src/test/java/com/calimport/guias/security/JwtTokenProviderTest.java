package com.calimport.guias.security;

import org.junit.jupiter.api.Test;

import com.calimport.guias.model.Rol;

import io.jsonwebtoken.Claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private static final String SECRET = "un-secret-de-prueba-con-mas-de-32-caracteres";
    private static final String OTRO_SECRET = "otro-secret-distinto-igual-de-largo-que-el-primero";

    private JwtTokenProvider provider(String secret, long ttlMinutes) {
        return new JwtTokenProvider(secret, ttlMinutes);
    }

    @Test
    void elTokenLlevaLosDatosDelRepartidorQueNecesitaElFrontend() {
        JwtTokenProvider provider = provider(SECRET, 60);

        String token = provider.generateToken("juan@calimport.cl", 7, "Juan Perez", Rol.REPARTIDOR);
        Claims claims = provider.parseToken(token);

        assertEquals("juan@calimport.cl", claims.getSubject());
        assertEquals("REPARTIDOR", claims.get("role", String.class));
        assertEquals(7, claims.get("employeeId", Integer.class));
        assertEquals("Juan Perez", claims.get("nombre", String.class));
    }

    @Test
    void elJefeDeBodegaViajaConSuRolEnElToken() {
        // El frontend compara contra este texto literal: no cambiarlo sin cambiarlo allá.
        JwtTokenProvider provider = provider(SECRET, 60);

        Claims claims = provider.parseToken(provider.generateToken("jefe@calimport.cl", 9, "Jefe", Rol.JEFE_BODEGA));

        assertEquals("JEFE_BODEGA", claims.get("role", String.class));
    }

    @Test
    void unRolAusenteODesconocidoSeLeeComoRepartidor() {
        assertEquals(Rol.REPARTIDOR, Rol.desdeClaim(null));
        assertEquals(Rol.REPARTIDOR, Rol.desdeClaim("ADMIN"));
        assertEquals(Rol.REPARTIDOR, Rol.desdeClaim("jefe_bodega"));
        assertEquals(Rol.JEFE_BODEGA, Rol.desdeClaim("JEFE_BODEGA"));
    }

    @Test
    void unSecretMasCortoQue32CaracteresNoDejaLevantarLaApp() {
        // Falla al construir el bean, no en la primera request: es a propósito.
        assertThrows(IllegalArgumentException.class, () -> provider("corto", 60));
        assertThrows(IllegalArgumentException.class, () -> provider(null, 60));
    }

    @Test
    void unTokenVencidoNoValida() {
        // TTL negativo: el token nace ya expirado.
        JwtTokenProvider provider = provider(SECRET, -1);

        String token = provider.generateToken("juan@calimport.cl", 7, "Juan Perez", Rol.REPARTIDOR);

        assertFalse(provider.validateToken(token));
    }

    @Test
    void unTokenFirmadoConOtroSecretNoValida() {
        String tokenAjeno = provider(OTRO_SECRET, 60).generateToken("juan@calimport.cl", 7, "Juan Perez", Rol.REPARTIDOR);

        assertFalse(provider(SECRET, 60).validateToken(tokenAjeno));
    }

    @Test
    void unTokenAlteradoNoValida() {
        JwtTokenProvider provider = provider(SECRET, 60);
        String token = provider.generateToken("juan@calimport.cl", 7, "Juan Perez", Rol.REPARTIDOR);

        // Se altera el payload sin volver a firmar: la firma deja de cuadrar.
        String[] partes = token.split("\\.");
        String alterado = partes[0] + "." + partes[1] + "x." + partes[2];

        assertFalse(provider.validateToken(alterado));
    }

    @Test
    void unTokenRecienEmitidoValida() {
        JwtTokenProvider provider = provider(SECRET, 60);

        assertTrue(provider.validateToken(provider.generateToken("juan@calimport.cl", 7, "Juan Perez", Rol.REPARTIDOR)));
    }

    @Test
    void basuraEnLugarDeUnTokenNoRevientaDevuelveFalse() {
        JwtTokenProvider provider = provider(SECRET, 60);

        assertFalse(provider.validateToken("esto-no-es-un-jwt"));
        assertFalse(provider.validateToken(""));
    }
}
