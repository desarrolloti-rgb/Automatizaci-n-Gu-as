package com.calimport.guias.sap;

/**
 * Los cuatro datos de una guía de despacho de SAP que la app necesita.
 *
 * <p>El folio es {@code FolioNumber}, no {@code DocNum}: {@code DocNum} es la numeración
 * interna de SAP, mientras que {@code FolioNumber} (con prefijo "GD") es el folio del
 * documento tributario — el número que va impreso en el papel que lleva el repartidor.
 *
 * <p>La dirección es {@code Address2}, la de <b>despacho</b>. Ojo con las otras dos que
 * trae el documento: {@code Address} es la de facturación, y {@code DocumentLines[].Address}
 * es la bodega de Calimport, o sea el origen. Al repartidor solo le sirve la de despacho.
 *
 * <p>El comentario es {@code Comments}: texto libre donde suele venir el horario de
 * recepción del cliente. Se interpreta recién al armar la ruta.
 */
public record GuiaSap(int docEntry, Long folio, String cliente, String direccion, String comentario) {
}
