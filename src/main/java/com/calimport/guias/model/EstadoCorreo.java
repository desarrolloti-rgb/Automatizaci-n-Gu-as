package com.calimport.guias.model;

/**
 * En qué quedó el correo que le avisa al cliente que su despacho se entregó. Una guía sin
 * entregar, o entregada antes de que existiera el correo, lo tiene en null: no corresponde.
 */
public enum EstadoCorreo {
    /** Por enviar, o reintentando después de una falla (ver {@code correoMotivo}). */
    PENDIENTE,
    ENVIADO,
    /** El cliente no tiene correo en su ficha de SAP. Reintentar no lo arregla: hay que cargarlo. */
    SIN_CORREO,
    /** Se agotaron los intentos. El último error queda en {@code correoMotivo}. */
    FALLIDO
}
