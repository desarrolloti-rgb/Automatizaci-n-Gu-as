package com.calimport.guias.service;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.calimport.guias.google.GeminiClient;

import tools.jackson.databind.JsonNode;

/**
 * Interpreta el comentario con Gemini. Es pagado; se activa con {@code rutas.comentarios=gemini}.
 *
 * <p>Gemini solo interpreta el texto. Lo que devuelve se valida acá: una hora mal formada o
 * un rango al revés se descartan, en vez de mandarle al optimizador una restricción que
 * nadie escribió.
 */
@Service
@ConditionalOnProperty(prefix = "rutas", name = "comentarios", havingValue = "gemini")
public class InterpreteGemini implements InterpreteComentarios {

    static final String INSTRUCCIONES = """
            Eres un asistente de logística de una distribuidora en Chile. Recibes el comentario
            que un vendedor escribió en una guía de despacho. Extrae:

            - desde / hasta: el horario en que el cliente recibe mercadería, en formato HH:mm
              de 24 horas. "Mañana" o "AM" sin horas es 09:00 a 13:00; "tarde" o "PM" sin horas
              es 14:00 a 18:00. "Antes de las 11" es desde null hasta 11:00. "Después de las 15"
              es desde 15:00 hasta null. Si no hay horario, ambos null.
            - nota: instrucciones útiles para el repartidor (a quién llamar, por dónde entrar,
              días en que no recibe, cómo descargar), en una frase corta y sin repetir el horario.
              null si no hay nada útil.

            No inventes datos que no estén en el comentario. Si el comentario no tiene relación
            con la entrega (por ejemplo, datos de facturación), devuelve todo null.
            """;

    static final Map<String, Object> ESQUEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "desde", Map.of("type", "STRING", "nullable", true, "description", "HH:mm"),
                    "hasta", Map.of("type", "STRING", "nullable", true, "description", "HH:mm"),
                    "nota", Map.of("type", "STRING", "nullable", true)),
            "required", List.of("desde", "hasta", "nota"));

    private final GeminiClient geminiClient;

    public InterpreteGemini(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    @Override
    public Interpretacion interpretar(String comentario) {
        JsonNode json = geminiClient.generarJson(INSTRUCCIONES, comentario, ESQUEMA);

        LocalTime desde = hora(json.get("desde"));
        LocalTime hasta = hora(json.get("hasta"));
        if (desde != null && hasta != null && !desde.isBefore(hasta)) {
            desde = null;
            hasta = null;
        }

        JsonNode nota = json.get("nota");
        String notaTexto = (nota == null || !nota.isString() || nota.asString().isBlank()) ? null : nota.asString().trim();
        return new Interpretacion(desde, hasta, notaTexto);
    }

    private static LocalTime hora(JsonNode valor) {
        if (valor == null || !valor.isString()) {
            return null;
        }
        try {
            return LocalTime.parse(valor.asString().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
