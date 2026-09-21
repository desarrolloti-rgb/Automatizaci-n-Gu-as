package com.calimport.guias.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Links para abrir la ruta en el celular del repartidor.
 *
 * <p>Waze no acepta varias paradas en un link: solo navega a un destino. Google Maps sí,
 * pero con tope, así que una ruta larga se entrega partida en tramos, donde cada tramo
 * arranca en la última parada del anterior.
 */
public final class LinksNavegacion {

    private LinksNavegacion() {
    }

    public record Punto(double latitud, double longitud) {
        String texto() {
            // Locale.ROOT: con el locale del servidor en español saldría "-33,45" y el link no sirve.
            return String.format(Locale.ROOT, "%.6f,%.6f", latitud, longitud);
        }
    }

    public static String waze(Punto destino) {
        return "https://waze.com/ul?ll=" + destino.texto() + "&navigate=yes";
    }

    public static String googleMaps(Punto destino) {
        return "https://www.google.com/maps/dir/?api=1&destination=" + destino.texto() + "&travelmode=driving";
    }

    /**
     * @param paradasPorTramo cuántas paradas entran en cada link, contando el destino
     */
    public static List<String> tramosGoogleMaps(Punto origen, List<Punto> paradas, int paradasPorTramo) {
        if (paradasPorTramo < 1) {
            throw new IllegalArgumentException("paradasPorTramo debe ser al menos 1");
        }
        List<String> tramos = new ArrayList<>();
        Punto desde = origen;
        for (int i = 0; i < paradas.size(); i += paradasPorTramo) {
            List<Punto> tramo = paradas.subList(i, Math.min(i + paradasPorTramo, paradas.size()));
            Punto destino = tramo.get(tramo.size() - 1);
            List<Punto> intermedias = tramo.subList(0, tramo.size() - 1);

            StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1")
                    .append("&origin=").append(desde.texto())
                    .append("&destination=").append(destino.texto())
                    .append("&travelmode=driving");
            if (!intermedias.isEmpty()) {
                // "|" va codificado: algunos lectores de QR y apps de mensajería cortan el link ahí.
                url.append("&waypoints=").append(intermedias.stream()
                        .map(Punto::texto)
                        .collect(Collectors.joining("%7C")));
            }
            tramos.add(url.toString());
            desde = destino;
        }
        return tramos;
    }
}
