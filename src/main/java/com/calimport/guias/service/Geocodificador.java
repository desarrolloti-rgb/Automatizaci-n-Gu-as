package com.calimport.guias.service;

import java.util.Optional;

/**
 * Convierte la dirección de despacho en coordenadas.
 *
 * <p>Hay dos implementaciones y se elige con {@code rutas.geocodificador}: {@code osm}
 * (OpenStreetMap, gratis, por defecto) y {@code google} (Geocoding API, pagada).
 */
public interface Geocodificador {

    /**
     * @param aproximada no se llegó al número de calle, solo a la calle o la comuna: el punto
     *                   puede estar a cuadras del cliente
     */
    record Ubicacion(double latitud, double longitud, boolean aproximada) {
    }

    /** Vacío si no se encuentra la dirección. Un error del servicio es una excepción, no un vacío. */
    Optional<Ubicacion> geocodificar(String direccion);
}
