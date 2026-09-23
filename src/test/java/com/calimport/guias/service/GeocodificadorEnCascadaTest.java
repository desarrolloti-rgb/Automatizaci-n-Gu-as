package com.calimport.guias.service;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.calimport.guias.service.Geocodificador.Ubicacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cuándo se paga Google y cuándo no. */
class GeocodificadorEnCascadaTest {

    private static final Ubicacion EXACTA = new Ubicacion(-33.45, -70.66, false);
    private static final Ubicacion APROXIMADA = new Ubicacion(-33.40, -70.60, true);

    /** Geocodificador de mentira que anota si lo llamaron. */
    private static final class Falso implements Geocodificador {
        private final Optional<Ubicacion> respuesta;
        private final RuntimeException error;
        private int llamadas;

        Falso(Optional<Ubicacion> respuesta) {
            this(respuesta, null);
        }

        Falso(Optional<Ubicacion> respuesta, RuntimeException error) {
            this.respuesta = respuesta;
            this.error = error;
        }

        @Override
        public Optional<Ubicacion> geocodificar(String direccion) {
            llamadas++;
            if (error != null) {
                throw error;
            }
            return respuesta;
        }
    }

    @Test
    void siOpenStreetMapEncuentraNoSeLlamaAGoogle() {
        Falso osm = new Falso(Optional.of(EXACTA));
        Falso google = new Falso(Optional.of(EXACTA));

        assertEquals(Optional.of(EXACTA), new GeocodificadorEnCascada(osm, google).geocodificar("Calle 1, Maipú"));

        assertEquals(0, google.llamadas, "no se paga si lo gratis resolvió");
    }

    @Test
    void unaUbicacionAproximadaDeOsmTampocoSePagaDeNuevo() {
        // Ya sirve: la guía entra a la ruta marcada para confirmar. La mayoría de las
        // direcciones caen acá, así que cobrarlas seria pagar por lo que ya estaba resuelto.
        Falso osm = new Falso(Optional.of(APROXIMADA));
        Falso google = new Falso(Optional.of(EXACTA));

        assertEquals(Optional.of(APROXIMADA),
                new GeocodificadorEnCascada(osm, google).geocodificar("Calle 1, Maipú"));

        assertEquals(0, google.llamadas);
    }

    @Test
    void siOpenStreetMapNoEncuentraNadaEntraGoogle() {
        // El caso real: "DOMINGO ARTEGA 276" está mal tipeado en SAP y Nominatim busca
        // literal. Hoy eso bloquea la ruta entera con un 422.
        Falso osm = new Falso(Optional.empty());
        Falso google = new Falso(Optional.of(EXACTA));

        assertEquals(Optional.of(EXACTA),
                new GeocodificadorEnCascada(osm, google).geocodificar("DOMINGO ARTEGA 276, MACUL"));

        assertEquals(1, google.llamadas);
    }

    @Test
    void siGoogleFallaLaDireccionQuedaSinUbicarPeroNoSePropagaElError() {
        // Sin API key o sin cuota, perder el respaldo pagado no puede dejar sin ruta a las
        // guías que el geocodificador gratuito sí resolvió.
        Falso osm = new Falso(Optional.empty());
        Falso google = new Falso(null, new IllegalStateException("Falta configurar GOOGLE_MAPS_API_KEY"));

        assertTrue(new GeocodificadorEnCascada(osm, google).geocodificar("Calle 1, Maipú").isEmpty());
    }

    @Test
    void siNingunoEncuentraQuedaVacio() {
        Falso osm = new Falso(Optional.empty());
        Falso google = new Falso(Optional.empty());

        assertTrue(new GeocodificadorEnCascada(osm, google).geocodificar("Una calle que no existe").isEmpty());
        assertEquals(1, google.llamadas);
    }
}
