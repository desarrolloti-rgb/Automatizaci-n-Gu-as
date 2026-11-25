package com.calimport.guias.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import com.calimport.guias.security.SesionComun.Datos;

class SesionComunTest {

    private static final String SECRETO = "secreto-compartido-solo-para-pruebas-0123456789";

    /**
     * Vector compartido con Panel-Facturadores (backend/test/sesionComun.test.js): el panel
     * emite y lee exactamente este valor. Si esta prueba falla después de un cambio, el panel
     * deja de entender la cookie de Guías (o al revés).
     */
    static final String TOKEN_DEL_PANEL = "eyJ2IjoxLCJlbWFpbCI6ImplZmVAY2FsaW1wb3J0LmNsIiwiZW1wbG95ZWVJZCI6OSwic2VsbG8iOiJv"
            + "bDFXV0prNkowRTJ3SWpBM0VQRGh3IiwiZXhwIjo0MTAyNDQ0ODAwfQ.lXLm2Xue8hlmR7rurW1q7fijYqRPle1vGu-80lsENE0";
    static final String SELLO_DEL_PANEL = "ol1WWJk6J0E2wIjA3EPDhw";
    /** exp del vector (2100-01-01) menos los 30 días de duración. */
    private static final Instant EMITIDO = Instant.ofEpochSecond(4_102_444_800L - 30L * 24 * 3600);

    private final SesionComun sesion = new SesionComun(SECRETO, "calimport_sesion", 30, true);

    @Test
    void emiteExactamenteLoMismoQueElPanel() {
        assertEquals(SELLO_DEL_PANEL, sesion.sello("clave-del-jefe"));
        assertEquals(TOKEN_DEL_PANEL, sesion.emitir("jefe@calimport.cl", 9, "clave-del-jefe", EMITIDO));
    }

    @Test
    void leeLaCookieQueEmiteElPanel() {
        Datos datos = sesion.verificar(TOKEN_DEL_PANEL, EMITIDO).orElseThrow();

        assertEquals("jefe@calimport.cl", datos.email());
        assertEquals(9, datos.employeeId());
        assertTrue(sesion.selloCoincide(datos, "clave-del-jefe"));
        // Los espacios alrededor no cuentan: el panel también recorta U_Password.
        assertTrue(sesion.selloCoincide(datos, "  clave-del-jefe "));
    }

    @Test
    void siCambiaLaClaveEnSapElSelloYaNoCoincide() {
        Datos datos = sesion.verificar(TOKEN_DEL_PANEL, EMITIDO).orElseThrow();

        assertFalse(sesion.selloCoincide(datos, "clave-nueva"));
        assertFalse(sesion.selloCoincide(datos, ""));
    }

    @Test
    void unaCookieVencidaNoSirve() {
        Instant justoAlVencer = Instant.ofEpochSecond(4_102_444_800L);

        assertEquals(Optional.empty(), sesion.verificar(TOKEN_DEL_PANEL, justoAlVencer));
    }

    @Test
    void unaCookieAlteradaNoSirve() {
        String[] partes = TOKEN_DEL_PANEL.split("\\.");
        // Otra carga con la firma original: por ejemplo, alguien que cambia el employeeId.
        String otraCarga = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"v\":1,\"email\":\"otro@calimport.cl\",\"employeeId\":1,\"sello\":\"x\",\"exp\":4102444800}".getBytes());

        assertEquals(Optional.empty(), sesion.verificar(otraCarga + "." + partes[1], EMITIDO));
        assertEquals(Optional.empty(), sesion.verificar(partes[0] + ".firma-inventada", EMITIDO));
        assertEquals(Optional.empty(), sesion.verificar(partes[0], EMITIDO));
        assertEquals(Optional.empty(), sesion.verificar(TOKEN_DEL_PANEL + ".extra", EMITIDO));
        assertEquals(Optional.empty(), sesion.verificar("", EMITIDO));
        assertEquals(Optional.empty(), sesion.verificar(null, EMITIDO));
    }

    @Test
    void conOtroSecretoLaCookieNoSirve() {
        // Es lo que separa a la instancia de prueba de producción si algún día se le activa.
        SesionComun otra = new SesionComun("otro-secreto-de-al-menos-32-caracteres-xx", "calimport_sesion", 30, true);

        assertEquals(Optional.empty(), otra.verificar(TOKEN_DEL_PANEL, EMITIDO));
    }

    @Test
    void sinSecretoQuedaApagada() {
        SesionComun apagada = new SesionComun("", "calimport_sesion", 30, true);

        assertFalse(apagada.activa());
        assertEquals(Optional.empty(), apagada.verificar(TOKEN_DEL_PANEL, EMITIDO));
    }

    @Test
    void unSecretoCortoNoArranca() {
        assertThrows(IllegalArgumentException.class, () -> new SesionComun("corto", "calimport_sesion", 30, true));
    }

    @Test
    void laCookieViajaATodoElDominioYLaPaginaNoLaLee() {
        ResponseCookie cookie = sesion.cookie("valor");

        assertEquals("calimport_sesion", cookie.getName());
        assertEquals("/", cookie.getPath(), "tiene que llegar a /gd/, al panel y al ingreso común");
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Lax", cookie.getSameSite());
        assertEquals(30L * 24 * 3600, cookie.getMaxAge().toSeconds());

        ResponseCookie borrada = sesion.borrar();
        assertEquals("/", borrada.getPath());
        assertEquals(0, borrada.getMaxAge().toSeconds());
    }
}
