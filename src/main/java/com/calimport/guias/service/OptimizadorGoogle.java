package com.calimport.guias.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.calimport.guias.config.RutasConfig;
import com.calimport.guias.google.RouteOptimizationClient;
import com.calimport.guias.model.Guia;
import com.calimport.guias.utils.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Traduce las guías a una consulta de Route Optimization y la respuesta a un orden de visita.
 * Es pagada; se activa con {@code rutas.optimizador=google}.
 *
 * <p>Los horarios van como ventanas <b>blandas</b> y no duras. Con ventanas duras, si un
 * horario no se puede cumplir (el repartidor sale tarde, dos clientes cierran a la misma
 * hora en puntas opuestas) Google descarta la guía y no aparece en la ruta. Con blandas,
 * la guía queda igual en el recorrido, llegar fuera de horario cuesta caro así que el
 * optimizador lo evita cuando puede, y cuando no puede se avisa.
 */
@Service
@ConditionalOnProperty(prefix = "rutas", name = "optimizador", havingValue = "google")
public class OptimizadorGoogle implements OptimizadorRutas {

    /** Costo de llegar una hora fuera de horario, frente a 60 por hora de manejo: se evita salvo que no haya alternativa. */
    static final double COSTO_HORA_FUERA_DE_HORARIO = 1000;
    static final double COSTO_HORA_RUTA = 60;
    static final double COSTO_KILOMETRO = 1;

    private final RouteOptimizationClient client;
    private final RutasConfig config;

    public OptimizadorGoogle(RouteOptimizationClient client, RutasConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public List<Visita> optimizar(LocalDate fecha, LocalTime horaSalida, List<Guia> guias) {
        JsonNode respuesta = client.optimizeTours(construirConsulta(fecha, horaSalida, guias));
        return leerRespuesta(respuesta, guias.size());
    }

    Map<String, Object> construirConsulta(LocalDate fecha, LocalTime horaSalida, List<Guia> guias) {
        ZoneId zona = config.zona();
        ZonedDateTime salida = ZonedDateTime.of(fecha, horaSalida, zona);
        ZonedDateTime finDelDia = ZonedDateTime.of(fecha, LocalTime.of(23, 59), zona);

        List<Map<String, Object>> shipments = new ArrayList<>();
        for (Guia guia : guias) {
            Map<String, Object> entrega = new LinkedHashMap<>();
            entrega.put("arrivalLocation", latLng(guia.getLatitud(), guia.getLongitud()));
            entrega.put("duration", (config.getMinutosPorParada() * 60) + "s");
            Map<String, Object> ventana = ventana(guia, fecha, salida, finDelDia);
            if (ventana != null) {
                entrega.put("timeWindows", List.of(ventana));
            }
            // Sin penaltyCost la guía es obligatoria: Google no puede dejarla fuera.
            shipments.add(Map.of(
                    "label", String.valueOf(guia.getId()),
                    "deliveries", List.of(entrega)));
        }

        Map<String, Object> vehiculo = new LinkedHashMap<>();
        vehiculo.put("travelMode", "DRIVING");
        vehiculo.put("startLocation", latLng(config.getOrigenLatitud(), config.getOrigenLongitud()));
        vehiculo.put("startTimeWindows", List.of(Map.of("startTime", timestamp(salida))));
        vehiculo.put("costPerHour", COSTO_HORA_RUTA);
        vehiculo.put("costPerKilometer", COSTO_KILOMETRO);
        // Sin endLocation: la ruta termina en el último cliente, la vuelta no se optimiza.

        return Map.of(
                "considerRoadTraffic", true,
                "model", Map.of(
                        "globalStartTime", timestamp(salida),
                        "globalEndTime", timestamp(finDelDia),
                        "shipments", shipments,
                        "vehicles", List.of(vehiculo)));
    }

    private Map<String, Object> ventana(Guia guia, LocalDate fecha, ZonedDateTime salida, ZonedDateTime finDelDia) {
        if (guia.getVentanaDesde() == null && guia.getVentanaHasta() == null) {
            return null;
        }
        Map<String, Object> ventana = new HashMap<>();
        if (guia.getVentanaDesde() != null) {
            ZonedDateTime desde = ZonedDateTime.of(fecha, guia.getVentanaDesde(), config.zona());
            // Una ventana que abrió antes de la salida ya está abierta: no restringe nada.
            if (desde.isAfter(salida)) {
                ventana.put("softStartTime", timestamp(desde));
                ventana.put("costPerHourBeforeSoftStartTime", COSTO_HORA_FUERA_DE_HORARIO);
            }
        }
        if (guia.getVentanaHasta() != null) {
            ZonedDateTime hasta = ZonedDateTime.of(fecha, guia.getVentanaHasta(), config.zona());
            // Si ya cerró antes de salir no hay forma de cumplirla; se avisa al leer la ruta.
            if (hasta.isAfter(salida) && hasta.isBefore(finDelDia)) {
                ventana.put("softEndTime", timestamp(hasta));
                ventana.put("costPerHourAfterSoftEndTime", COSTO_HORA_FUERA_DE_HORARIO);
            }
        }
        return ventana.isEmpty() ? null : ventana;
    }

    List<Visita> leerRespuesta(JsonNode respuesta, int guiasEnviadas) {
        if (respuesta == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Route Optimization no devolvió respuesta");
        }
        if (respuesta.path("skippedShipments").size() > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "No se pudo incluir en la ruta a las guías con id "
                            + etiquetas(respuesta.path("skippedShipments")));
        }

        List<Visita> visitas = new ArrayList<>();
        // En JSON, proto3 omite los campos en 0: vehicleIndex y shipmentIndex pueden no
        // venir. Por eso la guía se identifica por su label y no por su índice.
        for (JsonNode visita : respuesta.path("routes").path(0).path("visits")) {
            String label = visita.path("shipmentLabel").asString("");
            String inicio = visita.path("startTime").asString("");
            if (label.isEmpty() || inicio.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Respuesta inesperada de Route Optimization: " + visita);
            }
            visitas.add(new Visita(Long.valueOf(label), OffsetDateTime.parse(inicio).toInstant()));
        }

        if (visitas.size() != guiasEnviadas) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Route Optimization devolvió " + visitas.size()
                    + " paradas para " + guiasEnviadas + " guías");
        }
        return visitas;
    }

    private static String etiquetas(JsonNode omitidas) {
        List<String> labels = new ArrayList<>();
        omitidas.forEach(o -> labels.add(o.path("label").asString("?")));
        return String.join(", ", labels);
    }

    private static Map<String, Object> latLng(Double latitud, Double longitud) {
        return Map.of("latitude", latitud, "longitude", longitud);
    }

    private static String timestamp(ZonedDateTime momento) {
        return momento.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
