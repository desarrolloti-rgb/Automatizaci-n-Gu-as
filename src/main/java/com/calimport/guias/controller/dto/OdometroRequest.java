package com.calimport.guias.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Lo que marca el odómetro del auto, en km enteros. Viene una lectura o las dos: la que no
 * viene no se toca, y corregir una es volver a mandarla.
 */
public record OdometroRequest(
        @PositiveOrZero(message = "no puede ser negativo")
        @Max(value = 9_999_999, message = "tiene más dígitos que un odómetro") Integer salida,
        @PositiveOrZero(message = "no puede ser negativo")
        @Max(value = 9_999_999, message = "tiene más dígitos que un odómetro") Integer termino) {
}
