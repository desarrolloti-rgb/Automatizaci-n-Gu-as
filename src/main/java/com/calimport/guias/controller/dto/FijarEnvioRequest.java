package com.calimport.guias.controller.dto;

/**
 * El número de envío del transportista que bodega fija a mano para una guía, o {@code null} para
 * quitarlo y volver a buscar por el folio.
 */
public record FijarEnvioRequest(String numero) {
}
