package com.calimport.guias.google;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.calimport.guias.config.GoogleConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini en Vertex AI, pidiendo la respuesta como JSON con un esquema fijo. Igual que
 * SapClient, solo formula la consulta: qué se le pregunta y cómo se usa la respuesta lo
 * decide el service.
 */
@Component
public class GeminiClient {

    private final GoogleConfig config;
    private final GoogleAccessTokenProvider tokenProvider;
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiClient(GoogleConfig config, GoogleAccessTokenProvider tokenProvider) {
        this.config = config;
        this.tokenProvider = tokenProvider;
    }

    /**
     * @param instrucciones qué tiene que hacer el modelo (system instruction)
     * @param texto el dato a procesar
     * @param esquema esquema OpenAPI de la respuesta, en el formato de Vertex AI
     * @return el JSON que devolvió el modelo, ya parseado
     */
    public JsonNode generarJson(String instrucciones, String texto, Map<String, Object> esquema) {
        if (config.getProjectId() == null || config.getProjectId().isBlank()
                || config.getGeminiModel() == null || config.getGeminiModel().isBlank()) {
            throw new IllegalStateException("Falta configurar GCP_PROJECT_ID o GEMINI_MODEL");
        }

        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", instrucciones))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", texto)))),
                "generationConfig", Map.of(
                        // Temperatura 0: el mismo comentario tiene que dar siempre el mismo horario.
                        "temperature", 0,
                        "responseMimeType", "application/json",
                        "responseSchema", esquema));

        JsonNode respuesta = restClient.post()
                .uri(url())
                .header("Authorization", "Bearer " + tokenProvider.token())
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode textoRespuesta = respuesta == null ? null
                : respuesta.path("candidates").path(0).path("content").path("parts").path(0).get("text");
        if (textoRespuesta == null || !textoRespuesta.isString()) {
            throw new IllegalStateException("Gemini no devolvió texto: " + respuesta);
        }
        return mapper.readTree(textoRespuesta.asString());
    }

    private String url() {
        String location = config.getGeminiLocation();
        String host = "global".equals(location) ? "aiplatform.googleapis.com" : location + "-aiplatform.googleapis.com";
        return "https://" + host + "/v1/projects/" + config.getProjectId() + "/locations/" + location
                + "/publishers/google/models/" + config.getGeminiModel() + ":generateContent";
    }
}
