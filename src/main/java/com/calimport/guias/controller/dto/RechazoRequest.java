package com.calimport.guias.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Por qué el cliente rechazó la guía.
 *
 * <p>Es <b>obligatorio</b>: un rechazo sin explicación no le sirve a nadie en la oficina —
 * no se sabe si hay que reprogramar, si el pedido venía mal o si el cliente no estaba. Es la
 * evidencia del rechazo, igual que la foto lo es de la entrega.
 *
 * <p>500 caracteres, el largo de la columna. Se escribe en el celular y en la calle: alcanza
 * de sobra para una frase, que es lo que realmente se escribe ahí.
 */
public record RechazoRequest(@NotBlank @Size(max = 500) String motivo) {
}
