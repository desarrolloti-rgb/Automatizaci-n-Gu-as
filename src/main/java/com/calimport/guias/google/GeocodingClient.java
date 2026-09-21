package com.calimport.guias.google;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.calimport.guias.config.GoogleConfig;
import com.calimport.guias.service.Geocodificador;

import tools.jackson.databind.JsonNode;

/** Geocodificador con Google Geocoding API. Es pagado; se activa con {@code rutas.geocodificador=google}. */
@Component
@ConditionalOnProperty(prefix = "rutas", name = "geocodificador", havingValue = "google")
public class GeocodingClient implements Geocodificador {

    private final GoogleConfig config;
    private final RestClient restClient;

    public GeocodingClient(GoogleConfig config) {
        this.config = config;
        this.restClient = RestClient.builder().baseUrl("https://maps.googleapis.com/maps/api").build();
    }

    /**
     * Vacío si Google no encuentra la dirección.
     *
     * <p>{@code components=country:CL} evita que una calle que existe en otro país gane
     * sobre la chilena. Se marca como aproximada cuando Google no llegó al número de
     * calle (location_type distinto de ROOFTOP o RANGE_INTERPOLATED) o solo encontró una
     * coincidencia parcial: el punto puede estar a cuadras del cliente.
     */
    @Override
    public Optional<Ubicacion> geocodificar(String direccion) {
        if (config.getMapsApiKey() == null || config.getMapsApiKey().isBlank()) {
            throw new IllegalStateException("Falta configurar GOOGLE_MAPS_API_KEY");
        }

        JsonNode respuesta = restClient.get()
                .uri(uri -> uri.path("/geocode/json")
                        .queryParam("address", direccion)
                        .queryParam("components", "country:CL")
                        .queryParam("region", "cl")
                        .queryParam("key", config.getMapsApiKey())
                        .build())
                .retrieve()
                .body(JsonNode.class);

        String status = respuesta == null ? "" : respuesta.path("status").asText("");
        if ("ZERO_RESULTS".equals(status)) {
            return Optional.empty();
        }
        if (!"OK".equals(status)) {
            // REQUEST_DENIED, OVER_QUERY_LIMIT, etc.: es un problema de configuración, no de
            // la dirección, y no hay que culpar a la guía.
            throw new IllegalStateException("Geocoding respondió " + status + ": "
                    + respuesta.path("error_message").asText(""));
        }

        JsonNode resultado = respuesta.path("results").path(0);
        JsonNode location = resultado.path("geometry").path("location");
        String tipo = resultado.path("geometry").path("location_type").asText("");
        boolean aproximada = resultado.path("partial_match").asBoolean(false)
                || !("ROOFTOP".equals(tipo) || "RANGE_INTERPOLATED".equals(tipo));

        return Optional.of(new Ubicacion(location.path("lat").asDouble(), location.path("lng").asDouble(), aproximada));
    }
}
