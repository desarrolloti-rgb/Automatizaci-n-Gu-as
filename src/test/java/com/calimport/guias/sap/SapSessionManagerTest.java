package com.calimport.guias.sap;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La cookie de sesión de SAP.
 *
 * <p>Parece un detalle y no lo es: el Service Layer devuelve {@code B1SESSION} y
 * {@code ROUTEID} en dos encabezados distintos, y quedarse con uno solo rompe todas las
 * escrituras con un error que habla de transacciones y no de sesiones.
 */
class SapSessionManagerTest {

    @Test
    void juntaLasDosCookiesDelLogin() {
        List<String> respuesta = List.of(
                "B1SESSION=abc-123;HttpOnly;;Secure;SameSite=None",
                "ROUTEID=.node5; path=/;Secure;SameSite=None");

        assertEquals("B1SESSION=abc-123; ROUTEID=.node5", SapSessionManager.armarCookie(respuesta));
    }

    @Test
    void descartaLosAtributosQueSonSoloParaElNavegador() {
        // HttpOnly, Secure y SameSite le dicen a un navegador cómo guardar la cookie.
        // Reenviárselos a SAP en la petición no significa nada.
        List<String> respuesta = List.of("B1SESSION=abc-123;HttpOnly;;Secure;SameSite=None");

        assertEquals("B1SESSION=abc-123", SapSessionManager.armarCookie(respuesta));
    }

    @Test
    void conUnaSolaCookieSigueFuncionando() {
        // No todas las instalaciones van en clúster: sin balanceador no hay ROUTEID.
        assertEquals("B1SESSION=xyz", SapSessionManager.armarCookie(List.of("B1SESSION=xyz")));
    }
}
