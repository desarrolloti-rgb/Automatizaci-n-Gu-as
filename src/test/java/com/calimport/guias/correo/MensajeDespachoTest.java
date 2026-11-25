package com.calimport.guias.correo;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MensajeDespachoTest {

    private static Guia entregada() {
        Guia guia = new Guia(46263, 187350L, "Constructora Andes SpA", "San Nicolás 630, San Miguel");
        guia.setEstado(EstadoGuia.ENTREGADA);
        // 19:41 UTC del 4 de noviembre = 16:41 en Chile (horario de verano, UTC-3).
        guia.setFechaEntrega(Instant.parse("2026-11-04T19:41:21Z"));
        return guia;
    }

    @Test
    void elAsuntoSigueElPatronDeHes() {
        // "Solicitud de HES · Guía 123 · Calimport" allá; acá, lo mismo con lo que pasó.
        MensajeDespacho.Contenido c = MensajeDespacho.de(entregada(), true, false, null, true);

        assertEquals("Despacho entregado · Guía 187350 · Calimport", c.asunto());
    }

    @Test
    void llevaElMarcoDeLosCorreosDeHes() {
        // El cliente recibe de Calimport la solicitud de HES y este aviso: tienen que verse de
        // la misma empresa.
        String html = MensajeDespacho.de(entregada(), true, false, null, true).html();

        assertTrue(html.contains("cid:logo@calimport"));
        assertTrue(html.contains("#0A1633"), "azul marino de la marca");
        assertTrue(html.contains("#FF7400"), "naranja de la marca");
        assertTrue(html.contains(">Despacho entregado</td>"), "etiqueta junto al logo");
        assertTrue(html.contains("WWW.CALIMPORT.CL"), "pie con el sitio");
        assertTrue(html.contains("Equipo de Despachos"), "firma");
    }

    @Test
    void laHoraVaEnHoraDeChileNoEnUtc() {
        // La VM corre en UTC: sin convertir, el cliente leería 19:41 por una entrega de las 16:41.
        MensajeDespacho.Contenido c = MensajeDespacho.de(entregada(), true, false, null, true);

        assertTrue(c.texto().contains("04-11-2026 16:41"), c.texto());
        assertTrue(c.html().contains("04-11-2026 16:41"));
    }

    @Test
    void saludaAlClienteYLlevaFolioYDireccion() {
        MensajeDespacho.Contenido c = MensajeDespacho.de(entregada(), true, false, null, true);

        assertTrue(c.texto().startsWith("Estimado equipo de Constructora Andes SpA:"), c.texto());
        assertTrue(c.texto().contains("Guía 187350"));
        assertTrue(c.texto().contains("San Nicolás 630, San Miguel"));
        assertTrue(c.html().contains("San Nicolás 630, San Miguel"));
    }

    @Test
    void conFotoLaMuestraEnElCuerpoYLaDaPorAdjunta() {
        MensajeDespacho.Contenido c = MensajeDespacho.de(entregada(), true, false, null, true);

        assertTrue(c.html().contains("<img src=\"cid:" + MensajeDespacho.CID_FOTO + "\""), c.html());
        assertTrue(c.texto().contains("Respaldo de entrega"));
        assertTrue(c.texto().contains("Guía 187350 (adjunta)"));
    }

    @Test
    void sinFotoNoHayRespaldoNiImagen() {
        MensajeDespacho.Contenido c = MensajeDespacho.de(entregada(), false, false, null, true);

        assertFalse(c.html().contains(MensajeDespacho.CID_FOTO));
        assertFalse(c.html().contains("Respaldo de entrega"));
        assertFalse(c.texto().contains("Respaldo de entrega"));
    }

    @Test
    void sinCasillaDeRespuestaPideNoResponder() {
        assertTrue(MensajeDespacho.de(entregada(), true, false, null, true).texto().contains("por favor no lo respondan"));
        assertTrue(MensajeDespacho.de(entregada(), true, true, null, true).texto().contains("pueden responder a este correo"));
    }

    @Test
    void loQueVieneDeSapSeEscapaEnElHtml() {
        // El nombre y la dirección los escribe un vendedor en SAP: no pueden armar HTML.
        Guia guia = entregada();
        guia.setCliente("Ferretería <b>El Clavo</b> & Cía");

        String html = MensajeDespacho.de(guia, true, false, null, true).html();

        assertFalse(html.contains("<b>El Clavo</b>"));
        assertTrue(html.contains("Ferretería &lt;b&gt;El Clavo&lt;/b&gt; &amp; Cía"), html);
    }

    @Test
    void enPruebaAvisaComoHesAQuienSeLeHabriaEnviado() {
        MensajeDespacho.Contenido c = MensajeDespacho.de(entregada(), true, false, "compras@cliente.cl", true);

        assertTrue(c.asunto().startsWith("[PRUEBA] "));
        assertTrue(c.texto().startsWith("*** CORREO DE PRUEBA *** En producción se enviaría a: compras@cliente.cl"),
                c.texto());
        assertTrue(c.html().contains("<strong>Correo de prueba.</strong> En producción se enviaría a: compras@cliente.cl"));
    }

    @Test
    void sinLogoLaMarcaVaEnTexto() {
        // Un logo que falta no puede dejar al cliente sin el aviso.
        String html = MensajeDespacho.de(entregada(), true, false, null, false).html();

        assertFalse(html.contains("cid:" + MensajeDespacho.CID_LOGO));
        assertTrue(html.contains("calimport</span>"));
    }
}
