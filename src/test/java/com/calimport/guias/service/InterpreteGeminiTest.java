package com.calimport.guias.service;

import java.time.LocalTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.calimport.guias.google.GeminiClient;
import com.calimport.guias.service.InterpreteComentarios.Interpretacion;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterpreteGeminiTest {

    @Mock
    private GeminiClient geminiClient;

    private InterpreteGemini interprete;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        interprete = new InterpreteGemini(geminiClient);
    }

    private void geminiResponde(String comentario, String json) {
        when(geminiClient.generarJson(any(), eq(comentario), any())).thenReturn(mapper.readTree(json));
    }

    @Test
    void separaElHorarioDeLaNota() {
        geminiResponde("recibe 9 a 13, llamar a Juan", """
                {"desde": "09:00", "hasta": "13:00", "nota": " Llamar a Juan antes de llegar "}
                """);

        Interpretacion i = interprete.interpretar("recibe 9 a 13, llamar a Juan");

        assertEquals(LocalTime.of(9, 0), i.desde());
        assertEquals(LocalTime.of(13, 0), i.hasta());
        assertEquals("Llamar a Juan antes de llegar", i.nota());
    }

    @Test
    void aceptaUnHorarioAbiertoPorUnLado() {
        geminiResponde("antes de las 11", """
                {"desde": null, "hasta": "11:00", "nota": null}
                """);

        Interpretacion i = interprete.interpretar("antes de las 11");

        assertNull(i.desde());
        assertEquals(LocalTime.of(11, 0), i.hasta());
        assertNull(i.nota());
    }

    @Test
    void unaHoraMalFormadaSeDescartaEnVezDeInventarUnaRestriccion() {
        geminiResponde("x", """
                {"desde": "mañana", "hasta": "25:00", "nota": ""}
                """);

        Interpretacion i = interprete.interpretar("x");

        assertNull(i.desde());
        assertNull(i.hasta());
        assertNull(i.nota());
    }

    @Test
    void unRangoAlRevesSeDescartaCompleto() {
        geminiResponde("x", """
                {"desde": "15:00", "hasta": "10:00", "nota": "portón lateral"}
                """);

        Interpretacion i = interprete.interpretar("x");

        assertNull(i.desde());
        assertNull(i.hasta());
        assertEquals("portón lateral", i.nota());
    }
}
