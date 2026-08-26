package com.calimport.guias.model;

public enum EstadoGuia {

    //Estados de Guía

    /** Todavía no hay ningún intento de entrega. Estado inicial. */
    PENDIENTE,

    /** El cliente recibió todo y firmó la guía. */
    ENTREGADA,


    /** El cliente rechazó el despacho completo. */
    RECHAZADA,

}
