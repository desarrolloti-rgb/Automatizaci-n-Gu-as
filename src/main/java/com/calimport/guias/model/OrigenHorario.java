package com.calimport.guias.model;

/** De dónde salió el horario de recepción de una guía. */
public enum OrigenHorario {
    /** Lo cargó el bodeguero: manda sobre el comentario de SAP. */
    BODEGA,
    /** Se interpretó del comentario de la guía en SAP. */
    COMENTARIO
}
