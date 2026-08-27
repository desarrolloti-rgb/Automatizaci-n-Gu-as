package com.calimport.guias.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CrearGuiaRequest(
        int docEntry,
        @NotNull Long folio,
        @NotBlank String cliente,
        @NotBlank String direccion) {
}
