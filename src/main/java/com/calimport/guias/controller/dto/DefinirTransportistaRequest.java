package com.calimport.guias.controller.dto;

/**
 * El transportista externo que lleva la guía ("SAMEX"), o {@code null} para quitárselo. Tiene que
 * ser uno de {@code guias.transportistas}; las mayúsculas y los espacios de más no importan.
 */
public record DefinirTransportistaRequest(String transportista) {
}
