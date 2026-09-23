package com.calimport.guias.controller.dto;

/**
 * Cuerpo de {@code PATCH /api/guias/{id}/direccion}.
 *
 * <p>La dirección es obligatoria: a diferencia del horario, no se puede "devolver a SAP"
 * mandándola vacía, porque una guía sin dirección no se puede ubicar ni rutear. Para volver
 * a la de SAP se copia la del pie, que la guía sigue guardando.
 */
public record DefinirDireccionRequest(String direccion) {
}
