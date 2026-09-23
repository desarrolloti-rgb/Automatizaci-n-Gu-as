package com.calimport.guias.controller.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.calimport.guias.model.OrigenHorario;

/**
 * La ruta tal como la consume la app del repartidor.
 *
 * @param tramosGoogleMaps la ruta completa partida en links de Google Maps, en orden
 * @param advertencias lo que el bodeguero debería revisar antes de despachar
 */
public record RutaResponse(
        Long id,
        Integer repartidorId,
        LocalDate fecha,
        LocalTime horaSalida,
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
