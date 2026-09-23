package com.calimport.guias.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code PATCH /api/guias/{id}/direccion}.
 *
 * <p>La dirección es obligatoria: a diferencia del horario, no se puede "devolver a SAP"
 * mandándola vacía, porque una guía sin dirección no se puede ubicar ni rutear. Para volver
 * a la de SAP se copia la del pie, que la guía sigue guardando.
 */
public record DefinirDireccionRequest(
        // 255 es el largo de la columna: sin este tope, una dirección más larga no da un
        // 400 sino un 500, porque el error aparece recién al escribir en Postgres.
        @NotBlank @Size(max = 255) String direccion) {
}
