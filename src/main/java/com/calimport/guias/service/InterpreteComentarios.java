package com.calimport.guias.service;

import java.time.LocalTime;

/**
 * Lee el comentario libre de una guía ("recibe solo en la mañana", "llamar antes, portón
 * lateral") y lo separa en un horario que el optimizador pueda respetar y una nota para
 * el repartidor.
 *
 * <p>Se elige con {@code rutas.comentarios}: {@code reglas} (patrones fijos, gratis, por
 * defecto) o {@code gemini} (Vertex AI, pagado).
 */
public interface InterpreteComentarios {

    /** Todo puede venir en null: un comentario sin horario no es un error. */
    record Interpretacion(LocalTime desde, LocalTime hasta, String nota) {
    }

    Interpretacion interpretar(String comentario);
}
