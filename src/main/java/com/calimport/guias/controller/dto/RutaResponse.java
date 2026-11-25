package com.calimport.guias.controller.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.calimport.guias.model.OrigenHorario;

/**
 * La ruta tal como la consume la app del repartidor.
 *
 * @param vuelveABodega si el plan cuenta el regreso del último cliente a la bodega
 * @param kmPlanificados km del recorrido según el plan, con un decimal; null en rutas sin plan
 * @param minutosPlanificados de la salida al término según el plan; null si no se conoce el término
 * @param terminoEstimado hora a la que termina: al salir del último cliente, o al llegar a bodega
 * @param odometroSalida lo que marcaba el odómetro al salir, según el repartidor; null si no lo anotó
 * @param odometroSalidaHora a qué hora lo anotó: el respaldo de que se tomó al salir
 * @param odometroTermino lo que marcaba al terminar; null si no lo anotó
 * @param odometroTerminoHora a qué hora lo anotó
 * @param tramosGoogleMaps la ruta completa partida en links de Google Maps, en orden; con vuelta,
 *        el último termina en la bodega
 * @param advertencias lo que el bodeguero debería revisar antes de despachar
 */
public record RutaResponse(
        Long id,
        Integer repartidorId,
        LocalDate fecha,
        LocalTime horaSalida,
        boolean vuelveABodega,
        Double kmPlanificados,
        Integer minutosPlanificados,
        LocalTime terminoEstimado,
        Integer odometroSalida,
        LocalTime odometroSalidaHora,
        Integer odometroTermino,
        LocalTime odometroTerminoHora,
        List<Parada> paradas,
        List<String> tramosGoogleMaps,
        List<String> advertencias) {

    public record Parada(
            int orden,
            Long guiaId,
            Long folio,
            String cliente,
            String direccion,
            double latitud,
            double longitud,
            boolean ubicacionAproximada,
            LocalTime llegadaEstimada,
            LocalTime ventanaDesde,
            LocalTime ventanaHasta,
            OrigenHorario origenHorario,
            boolean fueraDeHorario,
            String nota,
            String comentario,
            /**
             * El pie del documento, entero. Es donde vienen el contacto y el teléfono de
             * quien recibe: en la calle, con el portón cerrado, es lo único que le sirve al
             * repartidor. Va crudo y no resumido — lo escribió alguien para que lo lean.
             */
            String footer,
            String wazeUrl,
            String googleMapsUrl) {
    }
}
