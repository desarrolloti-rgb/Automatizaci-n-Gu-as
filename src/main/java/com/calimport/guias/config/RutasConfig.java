package com.calimport.guias.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "rutas")
public class RutasConfig {

    /** Coordenadas de la bodega, desde donde sale el repartidor. */
    private Double origenLatitud;
    private Double origenLongitud;

    /** Tiempo estimado de descarga y firma en cada cliente. */
    private int minutosPorParada = 10;

    /**
     * Paradas por link de Google Maps. Maps acepta origen + 9 intermedias + destino en la
     * app y en escritorio, pero solo 3 intermedias si el link se abre en el navegador del
     * celular. Con la app instalada, 10 funciona.
     */
    private int paradasPorTramoMaps = 10;

    private String zonaHoraria = "America/Santiago";

    /**
     * Qué implementación usa cada paso. Por defecto las gratuitas; "google"/"gemini" activan
     * las pagadas (ver GoogleConfig). Solo se declaran acá para documentarlas: los beans se
     * eligen con {@code @ConditionalOnProperty}.
     */
    private String geocodificador = "osm";
    private String optimizador = "local";
    private String comentarios = "reglas";

    /**
     * Velocidad media puerta a puerta que usa el optimizador local, en km/h. No conoce el
     * tráfico: 25 es un promedio diurno razonable para Santiago; subirla adelanta las llegadas.
     */
    private double velocidadPromedioKmh = 25;

    /** Nominatim de OpenStreetMap. Su política exige un User-Agent que identifique la app. */
    private String osmUrl = "https://nominatim.openstreetmap.org";
    private String osmUserAgent = "calimport-guias/1.0";

    public boolean origenConfigurado() {
        return origenLatitud != null && origenLongitud != null;
    }

    public ZoneId zona() {
        return ZoneId.of(zonaHoraria);
    }

    public Double getOrigenLatitud() { return origenLatitud; }
    public void setOrigenLatitud(Double origenLatitud) { this.origenLatitud = origenLatitud; }

    public Double getOrigenLongitud() { return origenLongitud; }
    public void setOrigenLongitud(Double origenLongitud) { this.origenLongitud = origenLongitud; }

    public int getMinutosPorParada() { return minutosPorParada; }
    public void setMinutosPorParada(int minutosPorParada) { this.minutosPorParada = minutosPorParada; }

    public int getParadasPorTramoMaps() { return paradasPorTramoMaps; }
    public void setParadasPorTramoMaps(int paradasPorTramoMaps) { this.paradasPorTramoMaps = paradasPorTramoMaps; }

    public String getZonaHoraria() { return zonaHoraria; }
    public void setZonaHoraria(String zonaHoraria) { this.zonaHoraria = zonaHoraria; }

    public String getGeocodificador() { return geocodificador; }
    public void setGeocodificador(String geocodificador) { this.geocodificador = geocodificador; }

    public String getOptimizador() { return optimizador; }
    public void setOptimizador(String optimizador) { this.optimizador = optimizador; }

    public String getComentarios() { return comentarios; }
    public void setComentarios(String comentarios) { this.comentarios = comentarios; }

    public double getVelocidadPromedioKmh() { return velocidadPromedioKmh; }
    public void setVelocidadPromedioKmh(double velocidadPromedioKmh) { this.velocidadPromedioKmh = velocidadPromedioKmh; }

    public String getOsmUrl() { return osmUrl; }
    public void setOsmUrl(String osmUrl) { this.osmUrl = osmUrl; }

    public String getOsmUserAgent() { return osmUserAgent; }
    public void setOsmUserAgent(String osmUserAgent) { this.osmUserAgent = osmUserAgent; }
}
