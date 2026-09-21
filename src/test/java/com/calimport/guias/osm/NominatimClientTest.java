package com.calimport.guias.osm;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.calimport.guias.osm.NominatimClient.ConsultaEstructurada;
import com.calimport.guias.service.Geocodificador.Ubicacion;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cómo se arma la consulta y cómo se lee la respuesta de Nominatim, sin salir a la red. */
class NominatimClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parteLaDireccionDeSapEnCalleConNumeroYComuna() {
        assertEquals(Optional.of(new ConsultaEstructurada("1001 Av. Américo Vespucio", "Quilicura")),
                ConsultaEstructurada.de("Av. Américo Vespucio 1001, Quilicura, Santiago"));
        assertEquals(Optional.of(new ConsultaEstructurada("1236 Padre Orellana", "Santiago Centro")),
                ConsultaEstructurada.de("Padre Orellana 1236, Santiago Centro"));
    }

    @Test
    void aceptaNumerosConLetra() {
        assertEquals(Optional.of(new ConsultaEstructurada("630-B San Nicolás", "San Miguel")),
                ConsultaEstructurada.de("San Nicolás 630-B, San Miguel"));
    }

    @Test
    void sinNumeroOSinComunaQuedaParaLaBusquedaLibre() {
        assertTrue(ConsultaEstructurada.de("Av. Providencia, Providencia").isEmpty());
        assertTrue(ConsultaEstructurada.de("Av. Providencia 1208").isEmpty());
        assertTrue(ConsultaEstructurada.de("Av. Providencia 1208, ").isEmpty());
    }

    @Test
    void unaDireccionConNumeroEsExacta() {
        String json = """
                [{"lat": "-33.4584718", "lon": "-70.6319705", "place_rank": 30, "addresstype": "place"}]
                """;

        Optional<Ubicacion> u = NominatimClient.leerResultado(mapper.readTree(json));

        assertEquals(Optional.of(new Ubicacion(-33.4584718, -70.6319705, false)), u);
    }

    @Test
    void siSoloEncontroLaCalleEsAproximada() {
        String json = """
                [{"lat": "-33.3732", "lon": "-70.7251", "place_rank": 26, "addresstype": "road"}]
                """;

        assertTrue(NominatimClient.leerResultado(mapper.readTree(json)).orElseThrow().aproximada());
    }

    @Test
    void sinResultadosEsVacioYNoUnError() {
        assertTrue(NominatimClient.leerResultado(mapper.readTree("[]")).isEmpty());
    }

    @Test
    void unaRespuestaQueNoEsUnaListaEsUnError() {
        // Así responde Nominatim cuando rechaza la consulta: el problema no es la dirección.
        assertThrows(IllegalStateException.class,
                () -> NominatimClient.leerResultado(mapper.readTree("{\"error\": \"Too many requests\"}")));
    }
}
