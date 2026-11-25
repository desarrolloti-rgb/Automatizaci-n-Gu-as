package com.calimport.guias.sap;

import java.time.LocalDate;

import com.calimport.guias.model.OrigenDireccion;

/**
 * Los datos de una guía de despacho de SAP que la app necesita, ya elegidos y limpios.
 *
 * <p>El folio es {@code FolioNumber}, no {@code DocNum}: {@code DocNum} es la numeración
 * interna de SAP, mientras que {@code FolioNumber} (con prefijo "GD") es el folio del
 * documento tributario — el número que va impreso en el papel que lleva el repartidor.
 *
 * <p><b>La dirección sale del pie del documento</b> ({@code ClosingRemarks}, ver
 * {@link FooterDespacho}) cuando se puede ubicar, y si no de {@code Address2}, la de la
 * pestaña Logística. No al revés: Logística sale de la ficha del cliente y puede estar
 * desactualizada, mientras que el pie lo escribió alguien para <i>este</i> despacho.
 * {@link #origenDireccion} dice cuál de las dos quedó, para que bodega sepa qué confirmar.
 * Ojo con la tercera dirección del documento: {@code DocumentLines[].Address} es la bodega
 * de Calimport, o sea el origen.
 *
 * <p>El comentario junta el horario, el contacto y el teléfono del pie con {@code Comments}.
 * El pie crudo viaja aparte en {@link #footer}, junto con lo que se leyó de él
 * ({@link #direccionFooter}, {@link #horarioFooter}): es el respaldo que le permite a
 * bodega contrastar lo que la app entendió con lo que el documento dice.
 *
 * <p>{@link #fechaDocumento} es el {@code DocDate}: cuándo se creó la guía. Null si SAP no
 * lo trae o no se pudo leer.
 *
 * <p>{@link #despachador} es el {@code U_Despachador} que trae el documento: al crear la guía
 * en SAP se puede dejar ahí "SAMEX", y la app la marca como despacho externo al sincronizarla.
 * Null si el campo viene vacío o si no se pidió (los UDF de estado apagados).
 */
public record GuiaSap(int docEntry, Long folio, String cliente,
                      String direccion, OrigenDireccion origenDireccion, String comentario,
                      String footer, String direccionFooter, String horarioFooter,
                      LocalDate fechaDocumento, String despachador) {

    /** Sin despachador. */
    public GuiaSap(int docEntry, Long folio, String cliente,
                   String direccion, OrigenDireccion origenDireccion, String comentario,
                   String footer, String direccionFooter, String horarioFooter,
                   LocalDate fechaDocumento) {
        this(docEntry, folio, cliente, direccion, origenDireccion, comentario,
                footer, direccionFooter, horarioFooter, fechaDocumento, null);
    }

    /** Sin fecha del documento ni despachador. */
    public GuiaSap(int docEntry, Long folio, String cliente,
                   String direccion, OrigenDireccion origenDireccion, String comentario,
                   String footer, String direccionFooter, String horarioFooter) {
        this(docEntry, folio, cliente, direccion, origenDireccion, comentario,
                footer, direccionFooter, horarioFooter, null, null);
    }
}
