package com.calimport.guias.sap;

/**
 * Los cuatro datos de una guía de despacho de SAP que la app necesita.
 *
 * <p>El folio es {@code FolioNumber}, no {@code DocNum}: {@code DocNum} es la numeración
 * interna de SAP, mientras que {@code FolioNumber} (con prefijo "GD") es el folio del
 * documento tributario — el número que va impreso en el papel que lleva el repartidor.
 *
 * <p>La dirección es el {@code Address} de la cabecera, que es la del cliente. Ojo con no
 * confundirla con {@code DocumentLines[].Address}, que es la de Calimport (el origen).
 */
public record GuiaSap(int docEntry, Long folio, String cliente, String direccion) {
}
