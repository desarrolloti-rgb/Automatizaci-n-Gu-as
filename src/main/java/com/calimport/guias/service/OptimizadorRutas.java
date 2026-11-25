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
 *
 * <p><b>La vuelta a bodega</b> es opcional porque uno de los repartidores vuelve al terminar y
 * el otro no. Sin ella la ruta termina en el último cliente; con ella el tramo de regreso entra
 * al costo, así que el recorrido se ordena para terminar cerca de la bodega.
 */
public interface OptimizadorRutas {

    record Visita(Long guiaId, Instant llegada) {
    }

    /**
     * @param visitas las guías en el orden de visita, con la hora estimada de llegada a cada una
     * @param kilometros del recorrido completo, con la vuelta si la hay; null si el optimizador no lo informó
     * @param termino cuándo termina: al salir del último cliente, o al llegar a bodega si vuelve;
     *                null si el optimizador no lo informó
     */
    record Plan(List<Visita> visitas, Double kilometros, Instant termino) {
    }

    /**
     * @param guias todas con coordenadas
     */
    Plan optimizar(LocalDate fecha, LocalTime horaSalida, boolean vuelveABodega, List<Guia> guias);
}
