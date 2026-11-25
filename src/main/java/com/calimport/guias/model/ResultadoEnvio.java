package com.calimport.guias.model;

/** En qué quedó la última búsqueda de una guía en el portal de su transportista externo. */
public enum ResultadoEnvio {

    /** El transportista todavía no tiene un envío con el folio. Es lo normal recién marcada. */
    NO_ENCONTRADO,

    /**
     * No se pudo decidir solo: varios envíos con el folio, un envío que no lo tiene entre sus
     * referencias, o uno que ya es de otra guía. La guía no se toca; lo resuelve bodega.
     */
    CONFLICTO,

    /** El envío de la guía, con el folio confirmado entre sus referencias. */
    ASOCIADO
}
