package com.calimport.guias.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.calimport.guias.model.Guia;

/**
 * Ordena las guías del día de un repartidor, saliendo de la bodega.
 *
 * <p>Se elige con {@code rutas.optimizador}: {@code local} (cálculo propio, gratis, por
 * defecto) o {@code google} (Route Optimization API, pagada). Las dos tratan el horario de
 * recepción como ventana <b>blanda</b>: llegar fuera de horario cuesta caro, pero la guía
 * nunca queda fuera de la ruta.
 */
public interface OptimizadorRutas {

    record Visita(Long guiaId, Instant llegada) {
    }

    /**
     * @param guias todas con coordenadas
     * @return las guías en el orden de visita, con la hora estimada de llegada a cada una
     */
    List<Visita> optimizar(LocalDate fecha, LocalTime horaSalida, List<Guia> guias);
}
