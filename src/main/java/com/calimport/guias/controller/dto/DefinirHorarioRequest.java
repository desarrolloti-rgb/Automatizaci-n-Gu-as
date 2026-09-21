package com.calimport.guias.controller.dto;

import java.time.LocalTime;

/** Todo null devuelve el horario al comentario de SAP. */
public record DefinirHorarioRequest(LocalTime ventanaDesde, LocalTime ventanaHasta, String nota) {
}
