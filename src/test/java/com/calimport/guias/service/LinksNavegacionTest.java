package com.calimport.guias.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.calimport.guias.service.LinksNavegacion.Punto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinksNavegacionTest {

    private static final Punto BODEGA = new Punto(-33.4, -70.6);

    private static List<Punto> paradas(int cantidad) {
        List<Punto> puntos = new ArrayList<>();
        for (int i = 1; i <= cantidad; i++) {
            puntos.add(new Punto(-33.0 - i / 100.0, -70.0));
        }
        return puntos;
    }

    @Test
    void wazeNavegaDirectoALaParada() {
        assertEquals("https://waze.com/ul?ll=-33.450000,-70.660000&navigate=yes",
                LinksNavegacion.waze(new Punto(-33.45, -70.66)));
    }

    @Test
    void lasCoordenadasUsanPuntoAunqueElServidorEsteEnEspanol() {
        Locale anterior = Locale.getDefault();
        Locale.setDefault(Locale.of("es", "CL"));
        try {
            assertTrue(LinksNavegacion.googleMaps(new Punto(-33.45, -70.66)).contains("-33.450000,-70.660000"));
        } finally {
            Locale.setDefault(anterior);
        }
    }

    @Test
    void quinceParadasEnTramosDeDiezSonDosLinks() {
        List<String> tramos = LinksNavegacion.tramosGoogleMaps(BODEGA, paradas(15), 10);

        assertEquals(2, tramos.size());
        // Primer tramo: sale de la bodega, 9 intermedias y destino en la parada 10.
        assertTrue(tramos.get(0).contains("origin=-33.400000,-70.600000"));
        assertTrue(tramos.get(0).contains("destination=-33.100000,-70.000000"));
        assertEquals(8, tramos.get(0).split("%7C").length - 1);
        // El segundo arranca donde terminó el primero.
        assertTrue(tramos.get(1).contains("origin=-33.100000,-70.000000"));
        assertTrue(tramos.get(1).contains("destination=-33.150000,-70.000000"));
    }

    @Test
    void unTramoDeUnaSolaParadaNoLlevaWaypoints() {
        List<String> tramos = LinksNavegacion.tramosGoogleMaps(BODEGA, paradas(11), 10);

        assertEquals(2, tramos.size());
        assertFalse(tramos.get(1).contains("waypoints"));
    }

    @Test
    void sinParadasNoHayLinks() {
        assertTrue(LinksNavegacion.tramosGoogleMaps(BODEGA, List.of(), 10).isEmpty());
    }
}
