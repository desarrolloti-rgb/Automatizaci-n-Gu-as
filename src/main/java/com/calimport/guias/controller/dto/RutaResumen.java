package com.calimport.guias.controller.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.calimport.guias.model.EstadoRuta;

/**
 * Una fila del historial de rutas ({@code GET /api/rutas/historial}): lo que bodega mide de cada
 * ruta sin abrirla. El recorrido completo se pide aparte, con {@code GET /api/rutas}.
 *
 * @param repartidor el nombre, aunque ya no esté activo: el historial lo sigue nombrando
 * @param paradas cuántas tiene; una guía que salió dos veces el mismo día cuenta dos
 * @param kmRecorridos término menos salida del odómetro; null si falta alguna de las dos
 */
public record RutaResumen(
        Long id,
        Integer repartidorId,
        String repartidor,
        LocalDate fecha,
        LocalTime horaSalida,
        EstadoRuta estado,
        boolean vuelveABodega,
        int paradas,
        int entregadas,
        int rechazadas,
        int pendientes,
        Double kmPlanificados,
        Integer odometroSalida,
        Integer odometroTermino,
        Integer kmRecorridos) {
}
