package com.calimport.guias.sap;

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
        g.setMotivoRechazo("No tenían espacio en bodega");

        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(g, "Juan Pérez", null);

        assertEquals("R", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("No tenían espacio en bodega", cuerpo.get(EstadoLogistico.CAMPO_MOTIVO_RECHAZO));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_URL_FOTO));
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

    @Test
    void aunConDespachadorAManoUnaPendienteLoLimpia() {
        // Defensa del invariante: si la guía volvió a P, en SAP no puede quedar nadie
        // figurando como responsable, venga lo que venga en el parámetro.
        Map<String, Object> cuerpo = EstadoLogistico.cuerpoPatch(guia(), "Juan Pérez", null);

        assertEquals("P", cuerpo.get(EstadoLogistico.CAMPO_ESTADO));
        assertNull(cuerpo.get(EstadoLogistico.CAMPO_DESPACHADOR));
    }
}
