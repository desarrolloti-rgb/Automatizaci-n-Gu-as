package com.calimport.guias.service;

import java.time.LocalTime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.calimport.guias.service.InterpreteComentarios.Interpretacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Los comentarios reales de las guías de ejemplo y las variantes que más se repiten. */
class InterpreteReglasTest {

    private final InterpreteReglas interprete = new InterpreteReglas();

    private static LocalTime hora(String valor) {
        return valor == null || valor.isBlank() ? null : LocalTime.parse(valor);
    }

    @ParameterizedTest(name = "\"{0}\" → {1}–{2}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            // Guías de ejemplo del perfil local.
            "Recibe hasta las 12:00                              | -     | 12:00",
            "Entregar entre 14 y 18 hrs, preguntar por bodega    | 14:00 | 18:00",
            "Solo en la mañana, cierran a las 13:30              | 09:00 | 13:30",
            "Horario de recepción 10:00 a 17:00                  | 10:00 | 17:00",
            "No reciben después de las 16 hrs                    | -     | 16:00",
            "Retiro por andén 3, recibe de 8 a 11                | 08:00 | 11:00",
            "Recibe de 9 a 13 hrs                                | 09:00 | 13:00",
            "Solo en la tarde                                    | 14:00 | 18:00",
            // Variantes.
            "de 9:30 a 12:30                                     | 09:30 | 12:30",
            "RECIBE DE 8.30 A 13 HRS                             | 08:30 | 13:00",
            "desde las 9 hasta las 13                            | 09:00 | 13:00",
            "9-13 hrs                                            | 09:00 | 13:00",
            "antes de las 11                                     | -     | 11:00",
            "a partir de las 15                                  | 15:00 | -",
            "después de las 10:30                                | 10:30 | -",
            "de 2 a 6                                            | 14:00 | 18:00",
            "hasta las 5 pm                                      | -     | 17:00",
            "no reciben antes de las 10                          | 10:00 | -",
            "por la mañana, desde las 10                         | 10:00 | 13:00",
    })
    void leeElHorario(String comentario, String desde, String hasta) {
        Interpretacion i = interprete.interpretar(comentario);

        assertEquals(hora(desde), i.desde(), "desde");
        assertEquals(hora(hasta), i.hasta(), "hasta");
        assertNull(i.nota());
    }

    @ParameterizedTest(name = "\"{0}\" no tiene horario")
    @CsvSource(delimiter = '|', value = {
            "Coordinar con el guardia del subterráneo",
            "Llamar antes, portón por calle lateral",
            "Bodega 2, oficina 305",
            "Fono 2 2345 6789",
            "Factura a nombre de Comercial Dos",
            "no reciben de 13 a 14",
            "'  '",
    })
    void sinHorarioNoInventaNada(String comentario) {
        Interpretacion i = interprete.interpretar(comentario);

        assertNull(i.desde());
        assertNull(i.hasta());
    }

    @ParameterizedTest(name = "\"{0}\" se descarta")
    @CsvSource(delimiter = '|', value = {
            "de 15 a 10",
            "solo en la tarde, hasta las 12",
            "de 25 a 30",
    })
    void unRangoImposibleSeDescarta(String comentario) {
        Interpretacion i = interprete.interpretar(comentario);

        assertNull(i.desde());
        assertNull(i.hasta());
    }
}
