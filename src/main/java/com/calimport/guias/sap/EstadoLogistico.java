package com.calimport.guias.sap;

import java.util.HashMap;
import java.util.Map;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;

/**
 * Traduce el estado de una guía a los UDF de última milla de SAP, que es la fuente de
 * verdad del ciclo: Postgres solo amortigua la mala señal hasta que el dato llega allá.
 *
 * <p>SAP maneja cuatro estados en {@code U_EstadoLog} y la app maneja tres más un
 * booleano. No es una diferencia de modelo sino la misma información contada distinto:
 *
 * <pre>
 *   P  Pendiente   PENDIENTE y sin retirar
 *   T  Tomada      PENDIENTE y recibidaPorRepartidor
 *   E  Entregada   ENTREGADA
 *   R  Rechazada   RECHAZADA
 * </pre>
 *
 * <p>Se deriva en vez de guardarse: un cuarto valor en {@link EstadoGuia} sería una
 * segunda representación del mismo hecho que {@code recibidaPorRepartidor}, y dos copias
 * de un dato terminan desincronizadas. Acá el estado de SAP se calcula en el momento de
 * enviarlo, así que no puede quedar desfasado del estado real de la guía.
 *
 * <p>El campo de cada flujo se manda <b>solo cuando corresponde</b>, y los que dejan de
 * aplicar se mandan en null para que SAP los limpie: una guía que vuelve a pendiente no
 * puede conservar el despachador ni el motivo del rechazo anterior.
 */
public final class EstadoLogistico {

    public static final String CAMPO_ESTADO = "U_EstadoLog";
    public static final String CAMPO_DESPACHADOR = "U_Despachador";
    public static final String CAMPO_URL_FOTO = "U_UrlFoto";
    public static final String CAMPO_MOTIVO_RECHAZO = "U_MotivoRech";

    private EstadoLogistico() {
    }

    /** El valor de {@code U_EstadoLog} que le corresponde a la guía tal como está hoy. */
    public static String codigo(Guia guia) {
        return switch (guia.getEstado()) {
            case ENTREGADA -> "E";
            case RECHAZADA -> "R";
            case PENDIENTE -> guia.isRecibidaPorRepartidor() ? "T" : "P";
        };
    }

    /**
     * El cuerpo del PATCH para la guía en su estado actual.
     *
     * @param nombreDespachador quién la lleva, para {@code U_Despachador}. Se manda el
     *     nombre y no el employeeId porque el UDF lo lee una persona mirando el documento
     *     en SAP, donde un número no dice nada.
     * @param urlFotoPublica la URL con la que la evidencia es alcanzable desde fuera de la
     *     app; null mientras la foto solo exista en el disco del servidor.
     */
    public static Map<String, Object> cuerpoPatch(Guia guia, String nombreDespachador, String urlFotoPublica) {
        // HashMap y no Map.of: los nulls son parte del contrato (limpian el campo en SAP)
        // y Map.of los rechaza.
        Map<String, Object> campos = new HashMap<>();
        String codigo = codigo(guia);
        campos.put(CAMPO_ESTADO, codigo);

        // Quien la tiene en la mano deja de aplicar cuando la guía vuelve a estar pendiente
        // y sin retirar: ahí el despachador se limpia para que bodega pueda reasignarla.
        campos.put(CAMPO_DESPACHADOR, "P".equals(codigo) ? null : nombreDespachador);

        // Los otros dos son la evidencia de cómo terminó, y solo existen en su estado.
        campos.put(CAMPO_URL_FOTO, guia.getEstado() == EstadoGuia.ENTREGADA ? urlFotoPublica : null);
        campos.put(CAMPO_MOTIVO_RECHAZO,
                guia.getEstado() == EstadoGuia.RECHAZADA ? guia.getMotivoRechazo() : null);

        return campos;
    }
}
