package com.calimport.guias.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Acceso a GCP. Nada de esto se valida al arrancar: la app tiene que levantar aunque las
 * rutas no estén configuradas, y el error aparece recién al intentar generar una.
 */
@Configuration
@ConfigurationProperties(prefix = "google")
public class GoogleConfig {

    /** Proyecto de GCP donde están habilitadas Route Optimization y Vertex AI. */
    private String projectId;

    /** API key de Maps con Geocoding API habilitada. */
    private String mapsApiKey;

    /** Región de Vertex AI para Gemini. "global" usa el endpoint sin región. */
    private String geminiLocation = "global";

    private String geminiModel;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getMapsApiKey() { return mapsApiKey; }
    public void setMapsApiKey(String mapsApiKey) { this.mapsApiKey = mapsApiKey; }

    public String getGeminiLocation() { return geminiLocation; }
    public void setGeminiLocation(String geminiLocation) { this.geminiLocation = geminiLocation; }

    public String getGeminiModel() { return geminiModel; }
    public void setGeminiModel(String geminiModel) { this.geminiModel = geminiModel; }
}
