package com.calimport.guias.osm;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.calimport.guias.config.RutasConfig;
import com.calimport.guias.service.Geocodificador;

import tools.jackson.databind.JsonNode;

/**
 * Geocodificador gratuito con Nominatim, de OpenStreetMap. Es el que se usa por defecto
 * ({@code rutas.geocodificador=osm}).
 *
 * <p>La política del servidor público exige como máximo <b>una consulta por segundo</b> y un
 * User-Agent que identifique la app; si no se respeta, bloquea la IP. Por eso las consultas
 * van en fila, espaciadas. Como cada guía se geocodifica una sola vez (las coordenadas
 * quedan guardadas), el costo es la espera de la primera ruta con guías nuevas: unos 15
 * segundos para 15 guías.
 */
@Component
@ConditionalOnProperty(prefix = "rutas", name = "geocodificador", havingValue = "osm", matchIfMissing = true)
public class NominatimClient implements Geocodificador {

    private static final long MILIS_ENTRE_CONSULTAS = 1100;

    /** Nominatim da place_rank 30 a una dirección con número; calle, barrio o comuna quedan por debajo. */
    private static final int RANGO_DIRECCION_EXACTA = 30;

    /** "Av. Américo Vespucio 1001" → calle y número. El número puede traer letra ("1236-B"). */
    private static final Pattern CALLE_Y_NUMERO = Pattern.compile("^(.*\\D)\\s+(\\d+[\\w-]*)$");

    private final RestClient restClient;
    private long ultimaConsulta;

    public NominatimClient(RutasConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.restClient = RestClient.builder()
                .baseUrl(config.getOsmUrl())
                .defaultHeader("User-Agent", config.getOsmUserAgent())
                .requestFactory(factory)
                .build();
    }

    /**
     * Primero busca por partes (calle con número y comuna), que en Santiago es bastante más
     * precisa que el texto libre. Si no encuentra nada, prueba con la dirección completa.
     */
    @Override
    public synchronized Optional<Ubicacion> geocodificar(String direccion) {
        Optional<ConsultaEstructurada> estructurada = ConsultaEstructurada.de(direccion);
        if (estructurada.isPresent()) {
            ConsultaEstructurada c = estructurada.get();
            Optional<Ubicacion> ubicacion = leerResultado(buscar(uri -> uri
                    .queryParam("street", c.calle())
                    .queryParam("city", c.comuna())
                    .queryParam("country", "Chile")));
            if (ubicacion.isPresent()) {
                return ubicacion;
            }
        }
        return leerResultado(buscar(uri -> uri
                .queryParam("q", direccion)
                .queryParam("countrycodes", "cl")));
    }

    private JsonNode buscar(Function<UriBuilder, UriBuilder> parametros) {
        esperarTurno();
        return restClient.get()
                .uri(uri -> parametros.apply(uri.path("/search"))
                        .queryParam("format", "jsonv2")
                        .queryParam("limit", 1)
                        .build())
                .retrieve()
                .body(JsonNode.class);
    }

    private void esperarTurno() {
        long espera = ultimaConsulta + MILIS_ENTRE_CONSULTAS - System.currentTimeMillis();
        if (espera > 0) {
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Geocodificación interrumpida", e);
            }
        }
        ultimaConsulta = System.currentTimeMillis();
    }

    static Optional<Ubicacion> leerResultado(JsonNode respuesta) {
        if (respuesta == null || !respuesta.isArray()) {
            throw new IllegalStateException("Respuesta inesperada de Nominatim: " + respuesta);
        }
        if (respuesta.isEmpty()) {
            return Optional.empty();
        }
        JsonNode lugar = respuesta.get(0);
        boolean aproximada = lugar.path("place_rank").asInt(0) < RANGO_DIRECCION_EXACTA;
        return Optional.of(new Ubicacion(
                Double.parseDouble(lugar.path("lat").asString()),
                Double.parseDouble(lugar.path("lon").asString()),
                aproximada));
    }

    /**
     * La dirección como la guarda SAP, "Calle Número, Comuna[, Ciudad]", partida para la
     * búsqueda por partes. Nominatim espera la calle como "número nombre".
     */
    record ConsultaEstructurada(String calle, String comuna) {

        static Optional<ConsultaEstructurada> de(String direccion) {
            String[] partes = direccion.split(",");
            if (partes.length < 2) {
                return Optional.empty();
            }
            Matcher m = CALLE_Y_NUMERO.matcher(partes[0].trim());
            String comuna = partes[1].trim();
            if (!m.matches() || comuna.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ConsultaEstructurada(m.group(2) + " " + m.group(1).trim(), comuna));
        }
    }
}
