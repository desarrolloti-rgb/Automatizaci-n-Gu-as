package com.calimport.guias.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.calimport.guias.config.RutasConfig;
import com.calimport.guias.model.Guia;
import com.calimport.guias.service.OptimizadorRutas.Visita;
import com.calimport.guias.utils.ApiException;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La consulta y la respuesta de Route Optimization, sin salir a la red. */
class OptimizadorGoogleTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 9, 15);
    private static final LocalTime SALIDA = LocalTime.of(9, 30);

    private OptimizadorGoogle optimizador;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RutasConfig config = new RutasConfig();
        config.setOrigenLatitud(-33.40);
        config.setOrigenLongitud(-70.60);
        config.setMinutosPorParada(10);
        optimizador = new OptimizadorGoogle(null, config);
    }

    private static Guia guia(long id) {
        Guia guia = new Guia((int) id, 1000 + id, "Cliente " + id, "Direccion " + id);
        guia.setId(id);
        guia.setLatitud(-33.45);
        guia.setLongitud(-70.66);
        return guia;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entregaDe(Map<String, Object> consulta, int indice) {
        Map<String, Object> model = (Map<String, Object>) consulta.get("model");
        Map<String, Object> shipment = ((List<Map<String, Object>>) model.get("shipments")).get(indice);
        return ((List<Map<String, Object>>) shipment.get("deliveries")).get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void laConsultaSaleDeLaBodegaALaHoraIndicadaConHorarioDeChile() {
        Map<String, Object> consulta = optimizador.construirConsulta(FECHA, SALIDA, List.of(guia(1)));

        Map<String, Object> model = (Map<String, Object>) consulta.get("model");
        assertEquals("2026-09-15T09:30:00-03:00", model.get("globalStartTime"));
        Map<String, Object> vehiculo = ((List<Map<String, Object>>) model.get("vehicles")).get(0);
        assertEquals(Map.of("latitude", -33.40, "longitude", -70.60), vehiculo.get("startLocation"));
        assertEquals(true, consulta.get("considerRoadTraffic"));
        assertEquals("600s", entregaDe(consulta, 0).get("duration"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cadaGuiaVaEtiquetadaConSuIdYSinPenalidadParaQueSeaObligatoria() {
        Map<String, Object> consulta = optimizador.construirConsulta(FECHA, SALIDA, List.of(guia(42)));

        Map<String, Object> model = (Map<String, Object>) consulta.get("model");
        Map<String, Object> shipment = ((List<Map<String, Object>>) model.get("shipments")).get(0);
        assertEquals("42", shipment.get("label"));
        assertFalse(shipment.containsKey("penaltyCost"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void elHorarioVaComoVentanaBlanda() {
        // Una ventana dura haria que Google descarte la guia si no puede cumplirla.
        Guia guia = guia(1);
        guia.setVentanaDesde(LocalTime.of(10, 0));
        guia.setVentanaHasta(LocalTime.of(13, 0));

        Map<String, Object> ventana = ((List<Map<String, Object>>) entregaDe(
                optimizador.construirConsulta(FECHA, SALIDA, List.of(guia)), 0).get("timeWindows")).get(0);

        assertEquals("2026-09-15T10:00:00-03:00", ventana.get("softStartTime"));
        assertEquals("2026-09-15T13:00:00-03:00", ventana.get("softEndTime"));
        assertFalse(ventana.containsKey("startTime"));
        assertFalse(ventana.containsKey("endTime"));
    }

    @Test
    void unaGuiaSinHorarioNoLlevaVentana() {
        assertFalse(entregaDe(optimizador.construirConsulta(FECHA, SALIDA, List.of(guia(1))), 0)
                .containsKey("timeWindows"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unaVentanaQueYaAbrioAntesDeSalirSoloConservaElCierre() {
        Guia guia = guia(1);
        guia.setVentanaDesde(LocalTime.of(8, 0));
        guia.setVentanaHasta(LocalTime.of(12, 0));

        Map<String, Object> ventana = ((List<Map<String, Object>>) entregaDe(
                optimizador.construirConsulta(FECHA, SALIDA, List.of(guia)), 0).get("timeWindows")).get(0);

        assertFalse(ventana.containsKey("softStartTime"));
        assertTrue(ventana.containsKey("softEndTime"));
    }

    @Test
    void unaVentanaQueCerroAntesDeSalirNoSeEnvia() {
        // No hay forma de cumplirla; la ruta la marca fuera de horario al leerla.
        Guia guia = guia(1);
        guia.setVentanaHasta(LocalTime.of(9, 0));

        assertFalse(entregaDe(optimizador.construirConsulta(FECHA, SALIDA, List.of(guia)), 0)
                .containsKey("timeWindows"));
    }

    @Test
    void laRespuestaSeLeeEnOrdenDeVisitaPorEtiqueta() {
        // Sin shipmentIndex en la primera visita: proto3 omite los campos en 0.
        String json = """
                {"routes": [{"visits": [
                    {"shipmentLabel": "7", "startTime": "2026-09-15T13:05:00Z"},
                    {"shipmentIndex": 1, "shipmentLabel": "3", "startTime": "2026-09-15T13:40:12.5Z"}
                ]}]}
                """;

        List<Visita> visitas = optimizador.leerRespuesta(mapper.readTree(json), 2);

        assertEquals(List.of(
                new Visita(7L, Instant.parse("2026-09-15T13:05:00Z")),
                new Visita(3L, Instant.parse("2026-09-15T13:40:12.5Z"))), visitas);
    }

    @Test
    void siGoogleDejaGuiasFueraSeAvisaCualesEnVezDeDevolverUnaRutaIncompleta() {
        String json = """
                {"routes": [{"visits": []}], "skippedShipments": [{"index": 0, "label": "9"}]}
                """;

        ApiException e = assertThrows(ApiException.class, () -> optimizador.leerRespuesta(mapper.readTree(json), 1));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, e.getStatus());
        assertTrue(e.getMessage().contains("9"));
    }

    @Test
    void siFaltanParadasEnLaRespuestaEsUnErrorDeGoogle() {
        String json = """
                {"routes": [{"visits": [{"shipmentLabel": "7", "startTime": "2026-09-15T13:05:00Z"}]}]}
                """;

        ApiException e = assertThrows(ApiException.class, () -> optimizador.leerRespuesta(mapper.readTree(json), 2));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
    }
}
