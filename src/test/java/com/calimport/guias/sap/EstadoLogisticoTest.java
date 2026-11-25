package com.calimport.guias.sap;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La traducción entre el modelo de la app y los UDF de SAP. Es función pura: se prueba
 * entera sin Service Layer, que es justamente lo que no se puede probar acá.
 */
class EstadoLogisticoTest {

    private static Guia guia() {
        Guia guia = new Guia(1001, 5555L, "Cliente X", "Av. Siempre Viva 742");
        guia.setId(1L);
        guia.setRepartidorId(7);
        return guia;
    }

    @Test
    void unaGuiaSinRetirarEsPendiente() {
        assertEquals("P", EstadoLogistico.codigo(guia()));
    }

    @Test
    void retirarlaLaDejaTomadaAunqueSigaPendienteEnLaApp() {
        Guia g = guia();
        g.setRecibidaPorRepartidor(true);

        // Es el punto del diseño: T no es un estado propio nuestro, es PENDIENTE + retirada.
        assertEquals(EstadoGuia.PENDIENTE, g.getEstado());
        assertEquals("T", EstadoLogistico.codigo(g));
    }

    @Test
    void entregadaYRechazadaSeMapeanDirecto() {
        Guia entregada = guia();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        Guia rechazada = guia();
        rechazada.setEstado(EstadoGuia.RECHAZADA);

        assertEquals("E", EstadoLogistico.codigo(entregada));
        assertEquals("R", EstadoLogistico.codigo(rechazada));
    }

    // --- transportista externo: bodega eligió "Despacho externo: SAMEX" ---

    /** Como la deja GuiaService al marcarla: con SAMEX, sin repartidor y recibida. */
    private static Guia deSamex() {
        Guia guia = new Guia(1001, 5555L, "Cliente X", "Av. Siempre Viva 742");
        guia.setId(1L);
        guia.setTransportista("SAMEX");
        guia.setRecibidaPorRepartidor(true);
        return guia;
    }

    @Test
    void unaGuiaMarcadaSamexEsTomadaConSamexComoDespachador() {
        Guia g = deSamex();

        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(g, null, null);

        // Bodega se la entregó a Samex y quedó recibida: para SAP está tomada, por Samex.
        assertEquals("T", EstadoLogistico.codigo(g));
        assertEquals("T", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("SAMEX", cuerpo.get(EstadoLogistico.CAMPO_DESPACHADOR));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_URL_FOTO));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_MOTIVO_RECHAZO));
    }

    @Test
    void desmarcarlaLaVuelveAPendienteYLimpiaElDespachador() {
        Guia g = deSamex();
        g.setTransportista(null);
        g.setRecibidaPorRepartidor(false);

        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(g, null, null);

        assertEquals("P", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        // Va en null, no ausente: si no se manda, SAP seguiría diciendo SAMEX.
        assertTrue(cuerpo.containsKey(EstadoLogistico.CAMPO_DESPACHADOR));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_DESPACHADOR));
    }

    @Test
    void unaEntregaDeSamexLlevaSamexComoDespachadorYElLinkAlPdf() {
        Guia g = deSamex();
        g.setEstado(EstadoGuia.ENTREGADA);

        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(g, null,
                "https://apps.calimport.cl/gd/api/evidencia/guia-1-abc.pdf");

        assertEquals("E", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("SAMEX", cuerpo.get(EstadoLogistico.CAMPO_DESPACHADOR));
        assertEquals("https://apps.calimport.cl/gd/api/evidencia/guia-1-abc.pdf",
                cuerpo.get(EstadoLogistico.CAMPO_URL_FOTO));
    }

    @Test
    void elTransportistaGanaAlNombreQueLlegue() {
        // Una guía de Samex no tiene repartidor. Si igual llegara un nombre, en SAP tiene que
        // decir quién la lleva de verdad.
        Object despachador = EstadoLogistico.cuerpoPatch(deSamex(), "Juan Pérez", null)
                .get(EstadoLogistico.CAMPO_DESPACHADOR);

        assertEquals("SAMEX", despachador);
    }

    @Test
    void elCuerpoDeUnaTomaLlevaEstadoYDespachador() {
        Guia g = guia();
        g.setRecibidaPorRepartidor(true);

        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(g, "Juan Pérez", null);

        assertEquals("T", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("Juan Pérez", cuerpo.get(EstadoLogistico.CAMPO_DESPACHADOR));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_URL_FOTO));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_MOTIVO_RECHAZO));
    }

    @Test
    void elCuerpoDeUnaEntregaLlevaLaFotoYNoElMotivo() {
        Guia g = guia();
        g.setRecibidaPorRepartidor(true);
        g.setEstado(EstadoGuia.ENTREGADA);

        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(g, "Juan Pérez",
                "https://apps.calimport.cl/gd/api/fotos/guia-1-abc.jpg");

        assertEquals("E", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("https://apps.calimport.cl/gd/api/fotos/guia-1-abc.jpg",
                cuerpo.get(EstadoLogistico.CAMPO_URL_FOTO));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_MOTIVO_RECHAZO));
    }

    @Test
    void elCuerpoDeUnRechazoLlevaElMotivoYNoLaFoto() {
        Guia g = guia();
        g.setEstado(EstadoGuia.RECHAZADA);
        g.setMotivoRechazo("Cliente cerrado");

        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(g, "Juan Pérez", null);

        assertEquals("R", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("Cliente cerrado", cuerpo.get(EstadoLogistico.CAMPO_MOTIVO_RECHAZO));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_URL_FOTO));
    }

    @Test
    void unMotivoLargoSeRecortaAlTamanoDelUdf() {
        // U_MotivoRech mide 20 en productivo y Service Layer no avisa si no cabe: deja el
        // campo en null y responde 204, asi que el motivo se perderia sin error.
        Guia g = guia();
        g.setEstado(EstadoGuia.RECHAZADA);
        g.setMotivoRechazo("No tenían espacio en bodega");

        Object motivo = EstadoLogistico.cuerpoPatch(g, "Juan Pérez", null)
                .get(EstadoLogistico.CAMPO_MOTIVO_RECHAZO);

        assertEquals("No tenían espacio e…", motivo);
        assertEquals(EstadoLogistico.LARGO_MOTIVO_RECHAZO, motivo.toString().length());
        // Lo completo sigue en la app.
        assertEquals("No tenían espacio en bodega", g.getMotivoRechazo());
    }

    @Test
    void unNombreLargoSeRecortaAlTamanoDelUdf() {
        Guia g = guia();
        g.setRecibidaPorRepartidor(true);

        Object despachador = EstadoLogistico.cuerpoPatch(g, "Juan Carlos Pérez González", null)
                .get(EstadoLogistico.CAMPO_DESPACHADOR);

        assertTrue(despachador.toString().length() <= EstadoLogistico.LARGO_DESPACHADOR);
        assertTrue(despachador.toString().startsWith("Juan Carlos Pérez"));
    }

    @Test
    void volverAPendienteLimpiaDespachadorYMotivoEnSap() {
        // Como queda una guía después de que el jefe de bodega la reabre.
        Guia reabierta = new Guia(1001, 5555L, "Cliente X", "Av. Siempre Viva 742");
        reabierta.setId(1L);

        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(reabierta, null, null);

        assertEquals("P", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        // Las tres claves van presentes con null: es lo que borra el valor viejo en SAP.
        // Omitirlas dejaria el despachador y el motivo del intento anterior.
        assertTrue(cuerpo.containsKey(EstadoLogistico.CAMPO_DESPACHADOR));
        assertTrue(cuerpo.containsKey(EstadoLogistico.CAMPO_MOTIVO_RECHAZO));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_DESPACHADOR));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_MOTIVO_RECHAZO));
    }

    // --- fecha y hora de la entrega ---

    @Test
    void laEntregaVaEnHoraDeChileYAlMinuto() {
        Guia g = guia();
        g.setEstado(EstadoGuia.ENTREGADA);
        // 19:41:21 UTC = 16:41:21 en Santiago (horario de verano, UTC-3).
        g.setFechaEntrega(Instant.parse("2026-11-04T19:41:21Z"));

        Map<String, Object> campos = EstadoLogistico.camposFechaEntrega(g);

        assertEquals("2026-11-04", campos.get(EstadoLogistico.CAMPO_FECHA_ENTREGA));
        // Mismo formato en que Service Layer devuelve un UDF de hora: sin segundos reales.
        assertEquals("16:41:00", campos.get(EstadoLogistico.CAMPO_HORA_ENTREGA));
    }

    @Test
    void unaEntregaDeNocheNoSeCorreAlDiaSiguiente() {
        // La VM corre en UTC: 02:30 UTC del 5 es todavía el 4 a las 23:30 en Santiago.
        Guia g = guia();
        g.setEstado(EstadoGuia.ENTREGADA);
        g.setFechaEntrega(Instant.parse("2026-11-05T02:30:00Z"));

        Map<String, Object> campos = EstadoLogistico.camposFechaEntrega(g);

        assertEquals("2026-11-04", campos.get(EstadoLogistico.CAMPO_FECHA_ENTREGA));
        assertEquals("23:30:00", campos.get(EstadoLogistico.CAMPO_HORA_ENTREGA));
    }

    @Test
    void enInviernoTambienSeUsaLaHoraLocal() {
        // Julio: Chile está en UTC-4, no UTC-3. Un desfase fijo fallaría la mitad del año.
        Guia g = guia();
        g.setEstado(EstadoGuia.ENTREGADA);
        g.setFechaEntrega(Instant.parse("2026-07-15T14:05:00Z"));

        assertEquals("10:05:00", EstadoLogistico.camposFechaEntrega(g).get(EstadoLogistico.CAMPO_HORA_ENTREGA));
    }

    @Test
    void unaRechazadaNoLlevaFechaDeEntregaAunqueTengaFechaDeCierre() {
        // GuiaService también le pone fechaEntrega a un rechazo (es cuándo se resolvió),
        // pero en SAP los campos son de la entrega y van vacíos, como la foto.
        Guia g = guia();
        g.setEstado(EstadoGuia.RECHAZADA);
        g.setFechaEntrega(Instant.parse("2026-11-04T19:41:21Z"));

        Map<String, Object> campos = EstadoLogistico.camposFechaEntrega(g);

        assertTrue(campos.containsKey(EstadoLogistico.CAMPO_FECHA_ENTREGA));
        assertNull(campos.get(EstadoLogistico.CAMPO_FECHA_ENTREGA));
        assertNull(campos.get(EstadoLogistico.CAMPO_HORA_ENTREGA));
    }

    @Test
    void unaPendienteLosMandaEnNullParaQueSapLosLimpie() {
        Map<String, Object> campos = EstadoLogistico.camposFechaEntrega(guia());

        // Presentes con null, no ausentes: es lo que borra un valor anterior en SAP.
        assertTrue(campos.containsKey(EstadoLogistico.CAMPO_FECHA_ENTREGA));
        assertTrue(campos.containsKey(EstadoLogistico.CAMPO_HORA_ENTREGA));
        assertNull(campos.get(EstadoLogistico.CAMPO_FECHA_ENTREGA));
        assertNull(campos.get(EstadoLogistico.CAMPO_HORA_ENTREGA));
    }

    @Test
    void aunConDespachadorAManoUnaPendienteLoLimpia() {
        // Defensa del invariante: si la guía volvió a P, en SAP no puede quedar nadie
        // figurando como responsable, venga lo que venga en el parámetro.
        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(guia(), "Juan Pérez", null);

        assertEquals("P", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_DESPACHADOR));
    }
}
