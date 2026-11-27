package com.calimport.guias.model;

import org.junit.jupiter.api.Test;

import static com.calimport.guias.model.EstadoRuta.COMPLETADA;
import static com.calimport.guias.model.EstadoRuta.CON_PENDIENTES;
import static com.calimport.guias.model.EstadoRuta.EN_CURSO;
import static com.calimport.guias.model.EstadoRuta.PLANIFICADA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Una fila por regla de D-01 en docs/planes/historial-de-rutas.md. */
class EstadoRutaTest {

    private static final boolean VUELVE = true;
    private static final boolean NO_VUELVE = false;

    @Test
    void sinSalirNiResolverNadaEstaPlanificada() {
        assertEquals(PLANIFICADA, EstadoRuta.de(5, 0, NO_VUELVE, false, false, false));
    }

    @Test
    void conElOdometroDeSalidaYaEstaEnCurso() {
        assertEquals(EN_CURSO, EstadoRuta.de(5, 0, NO_VUELVE, true, false, false));
    }

    @Test
    void conUnaParadaResueltaEstaEnCursoAunqueNoHayaAnotadoElOdometro() {
        assertEquals(EN_CURSO, EstadoRuta.de(5, 1, NO_VUELVE, false, false, false));
    }

    @Test
    void sinVueltaSeCompletaAlResolverLaUltimaParada() {
        assertEquals(COMPLETADA, EstadoRuta.de(5, 5, NO_VUELVE, true, false, false));
    }

    @Test
    void conVueltaFaltaElOdometroDeTerminoParaCompletarse() {
        assertEquals(EN_CURSO, EstadoRuta.de(5, 5, VUELVE, true, false, false));
        assertEquals(COMPLETADA, EstadoRuta.de(5, 5, VUELVE, true, true, false));
    }

    @Test
    void unDiaPasadoSinCompletarQuedaConPendientes() {
        assertEquals(CON_PENDIENTES, EstadoRuta.de(5, 3, NO_VUELVE, true, false, true));
        assertEquals(CON_PENDIENTES, EstadoRuta.de(5, 0, NO_VUELVE, false, false, true));
        assertEquals(CON_PENDIENTES, EstadoRuta.de(5, 5, VUELVE, true, false, true));
    }

    @Test
    void unDiaPasadoCompletoSigueCompletado() {
        assertEquals(COMPLETADA, EstadoRuta.de(5, 5, NO_VUELVE, true, false, true));
    }

    @Test
    void unaRutaSinParadasNoQuedaNuncaComoPendiente() {
        assertEquals(PLANIFICADA, EstadoRuta.de(0, 0, NO_VUELVE, false, false, true));
    }
}
