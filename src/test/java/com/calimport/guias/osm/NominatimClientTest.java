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
    void laComunaDeUnaDireccionConPuntoYGuionSeLeeIgual() {
        String limpia = NominatimClient.limpiar(
                "CARRETERA GENERAL SAN MARTIN # 16500 LOTEO LOS LIB. 15-A, COLINA - SANTIAGO");

        assertEquals(Optional.of(new ConsultaEstructurada("16500 CARRETERA GENERAL SAN MARTIN", "COLINA")),
                ConsultaEstructurada.de(limpia));
    }

    // Las direcciones de estos tests son las que bloquearon una ruta real con un 422.

    @Test
    void limpiaLoQueNominatimNoEntiende() {
        assertEquals("CAMINO LONGITUDINAL SUR 5201, SAN BERNARDO, SANTIAGO",
                NominatimClient.limpiar("CAMINO LONGITUDINAL SUR # 5201, SAN BERNARDO, SANTIAGO, CHILE"));
        assertEquals("CARRETERA GENERAL SAN MARTIN 16500, COLINA, SANTIAGO",
                NominatimClient.limpiar("CARRETERA GENERAL SAN MARTIN # 16500 LOTEO LOS LIB. 15-A, COLINA - SANTIAGO"));
        assertEquals("Los Aromos 120, Maipú", NominatimClient.limpiar("Los Aromos N° 120, Maipú"));
    }

    @Test
    void sacaElHorarioYElAgendamiento() {
        assertEquals("CANAL LA PUNTA 8770, RENCA", NominatimClient.limpiar(
                "CANAL LA PUNTA 8770, RENCA (BODEGA 51), LUNES A VIERNES DE 8.00 A 17.00, VIERNES HASTA LAS 14.00HRS"));
        assertEquals("Bodega Consolidación Lo Aguirre KM 16 Ruta 68 1200 Santiago, Pudahel", NominatimClient.limpiar(
                "Bodega Consolidación Lo Aguirre KM 16 Ruta 68 1200 Santiago/Pudahel Agendada para entrega "
                        + "Bodega Consolidacion el Lunes 31 Marzo a las 14:30Hrs"));
    }

    @Test
    void unaDireccionBienEscritaNoCambia() {
        assertEquals("Av. Américo Vespucio 1001, Quilicura, Santiago",
                NominatimClient.limpiar("Av. Américo Vespucio 1001, Quilicura, Santiago"));
        assertEquals("San Nicolás 630-B, San Miguel", NominatimClient.limpiar("San Nicolás 630-B, San Miguel"));
    }

    @Test
    void laComunaEsElPrimerTrozoSinNumerosQueNoEsUnCamino() {
        assertEquals(Optional.of("SAN JOAQUIN"), NominatimClient.comuna(
                NominatimClient.limpiar("AV. VICUÑA MACKENA 2289, SAN JOAQUIN, SANTIAGO, CHILE")));
        assertEquals(Optional.of("SAN FRANCISCO MOSTAZAL"), NominatimClient.comuna(NominatimClient.limpiar(
                "Bodega Planta, SITE CPP (Promedio) KM. 63 LONGITUDINAL SUR - SAN FRANCISCO MOSTAZAL")));
        assertEquals(Optional.of("RENGO"), NominatimClient.comuna(
                NominatimClient.limpiar("RUTA H-50 KM 0,2 - CAMINO QUINTA DE TILCOCO - RENGO")));
    }

    @Test
    void sinTrozoDespuesDeLaCalleNoHayComuna() {
        assertTrue(NominatimClient.comuna("Av. Providencia 1208").isEmpty());
        assertTrue(NominatimClient.comuna("Ruta 68 KM 16, Camino a Valparaíso").isEmpty());
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
