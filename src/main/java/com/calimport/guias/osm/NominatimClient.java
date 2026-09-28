package com.calimport.guias.osm;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /** Lo que el vendedor agrega después de la dirección: agendamiento u horario de recepción. */
    private static final Pattern AGENDA_U_HORARIO = Pattern.compile(
            "\\b(?:agendad\\w*|horarios?|(?:de\\s+)?lunes\\s+a)\\b.*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** "16500 LOTEO LOS LIB. 15-A" → "16500": lo que sigue al número es interno del recinto. */
    private static final Pattern DETALLE_TRAS_EL_NUMERO = Pattern.compile(
            "(\\d+[\\w-]*)\\s+(?:loteo|depto|dpto|departamento|of|oficina|local|bodega|piso|block"
                    + "|casa|sitio|parcela|m[oó]dulo)\\b[^,]*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** "calle, comuna, ciudad", "comuna - ciudad" o "Santiago/Pudahuel". */
    private static final Pattern SEPARADOR = Pattern.compile("\\s*(?:,|/|\\s-\\s)\\s*");

    /** Un trozo que empieza así es un camino, no una comuna ("CAMINO QUINTA DE TILCOCO"). */
    private static final Pattern EMPIEZA_COMO_CALLE = Pattern.compile(
            "^(?:calle|camino|avenida|avda|av|ruta|carretera|autopista|pasaje|psje)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
     * Las dos van sobre la dirección {@link #limpiar limpia}.
     */
    @Override
    public synchronized Optional<Ubicacion> geocodificar(String direccion) {
        String limpia = limpiar(direccion);
        Optional<ConsultaEstructurada> estructurada = ConsultaEstructurada.de(limpia);
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
                .queryParam("q", limpia)
                .queryParam("countrycodes", "cl")));
    }

    /**
     * Solo la comuna. Rescata los errores de tipeo ("VICUÑA MACKENA") y las direcciones
     * rurales por kilómetro, que Nominatim no tiene: con el punto en la comuna la guía entra
     * a la ruta marcada como aproximada, en vez de bloquearla entera.
     */
    @Override
    public synchronized Optional<Ubicacion> aproximar(String direccion) {
        Optional<String> comuna = comuna(limpiar(direccion));
        if (comuna.isEmpty()) {
            return Optional.empty();
        }
        return leerResultado(buscar(uri -> uri
                .queryParam("city", comuna.get())
                .queryParam("country", "Chile")))
                .map(u -> new Ubicacion(u.latitud(), u.longitud(), true));
    }

    /**
     * Deja la dirección de SAP en algo que Nominatim, que busca literal, pueda encontrar:
     * sin agendamiento ni horario, sin paréntesis, sin "#" ni "N°", sin lo que va después del
     * número ("LOTEO LOS LIB. 15-A") y sin "Chile", que la búsqueda ya fija.
     */
    static String limpiar(String direccion) {
        String limpia = AGENDA_U_HORARIO.matcher(direccion).replaceAll("");
        limpia = limpia.replaceAll("\\([^)]*\\)", " ")
                .replaceAll("#|\\b[Nn][°º]", " ");
        limpia = DETALLE_TRAS_EL_NUMERO.matcher(limpia).replaceAll("$1");
        // Todos los separadores quedan como ", ": así la calle y la comuna se parten igual.
        return SEPARADOR.splitAsStream(limpia.replaceAll("\\s+", " ").trim())
                .map(String::trim)
                .filter(p -> !p.isEmpty() && !p.equalsIgnoreCase("chile"))
                .collect(Collectors.joining(", "));
    }

    /**
     * La comuna es el primer trozo después de la calle que no tiene números ni empieza como
     * un camino: en "AV. VICUÑA MACKENA 2289, SAN JOAQUIN, SANTIAGO" es San Joaquín, no
     * Santiago; en "RUTA H-50 KM 0,2 - CAMINO QUINTA DE TILCOCO - RENGO", Rengo.
     */
    static Optional<String> comuna(String limpia) {
        String[] partes = limpia.split(",");
        for (int i = 1; i < partes.length; i++) {
            String parte = partes[i].trim();
            if (!parte.isEmpty() && !parte.matches(".*\\d.*") && !EMPIEZA_COMO_CALLE.matcher(parte).matches()) {
                return Optional.of(parte);
            }
        }
        return Optional.empty();
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
     * La dirección limpia, "Calle Número, Comuna[, Ciudad]", partida para la búsqueda por
     * partes. Nominatim espera la calle como "número nombre".
     */
    record ConsultaEstructurada(String calle, String comuna) {

        static Optional<ConsultaEstructurada> de(String limpia) {
            Matcher m = CALLE_Y_NUMERO.matcher(limpia.split(",")[0].trim());
            Optional<String> comuna = NominatimClient.comuna(limpia);
            if (!m.matches() || comuna.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ConsultaEstructurada(m.group(2) + " " + m.group(1).trim(), comuna.get()));
        }
    }
}
