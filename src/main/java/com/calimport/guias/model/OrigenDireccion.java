package com.calimport.guias.model;

/**
 * De dónde salió la dirección con la que se rutea una guía.
 *
 * <p>Existe porque las dos fuentes no valen lo mismo. La dirección de la pestaña
 * <b>Logística</b> del documento sale de la ficha del cliente y puede estar desactualizada;
 * la que el vendedor escribió en el pie ("DESPACHAR A") es la de este despacho, y la que
 * corrigió bodega es la única que alguien miró.
 */
public enum OrigenDireccion {
    /** La corrigió el jefe de bodega. Manda sobre las otras dos: ninguna sincronización la pisa. */
    BODEGA,
    /** La escribió el vendedor en el pie del documento, para este despacho. */
    FOOTER,
    /** La ficha del cliente (pestaña Logística). Es la que conviene confirmar contra el pie. */
    LOGISTICA
}
