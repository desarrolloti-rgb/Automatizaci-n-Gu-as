package com.calimport.guias.controller.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda el bodeguero al asignarle las guías del día a un repartidor.
 *
 * @param horaSalida cambia todos los días, por eso viene en la request y no en configuración
 */
public record GenerarRutaRequest(
        @NotNull Integer repartidorId,
        @NotNull LocalDate fecha,
        @NotNull LocalTime horaSalida,
        @NotEmpty @Size(max = 50) List<@Valid @NotNull GuiaEnRuta> guias) {

    /**
     * Horario y nota son opcionales: si el bodeguero los carga, mandan sobre el comentario
     * de SAP; si no, se usa lo que diga el comentario.
     */
    public record GuiaEnRuta(@NotNull Long guiaId, LocalTime ventanaDesde, LocalTime ventanaHasta, String nota) {

        public boolean traeHorario() {
            return ventanaDesde != null || ventanaHasta != null || (nota != null && !nota.isBlank());
        }
    }
}
