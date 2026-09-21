package com.calimport.guias.google;

import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.calimport.guias.config.GoogleConfig;

import tools.jackson.databind.JsonNode;

/**
 * Google Route Optimization API ({@code optimizeTours}). El cuerpo de la consulta lo arma
 * {@link com.calimport.guias.service.OptimizadorRutas}; acá solo se envía.
 */
@Component
public class RouteOptimizationClient {

    private final GoogleConfig config;
    private final GoogleAccessTokenProvider tokenProvider;
    private final RestClient restClient;

    public RouteOptimizationClient(GoogleConfig config, GoogleAccessTokenProvider tokenProvider) {
        this.config = config;
        this.tokenProvider = tokenProvider;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        // Resolver una ruta con tráfico tarda unos segundos; 60 deja margen sin colgar la request.
        factory.setReadTimeout(60_000);
        this.restClient = RestClient.builder()
                .baseUrl("https://routeoptimization.googleapis.com/v1")
                .requestFactory(factory)
                .build();
    }

    public JsonNode optimizeTours(Map<String, Object> body) {
        if (config.getProjectId() == null || config.getProjectId().isBlank()) {
            throw new IllegalStateException("Falta configurar GCP_PROJECT_ID");
        }
        return restClient.post()
                .uri("/projects/{proyecto}:optimizeTours", config.getProjectId())
                .header("Authorization", "Bearer " + tokenProvider.token())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }
}
