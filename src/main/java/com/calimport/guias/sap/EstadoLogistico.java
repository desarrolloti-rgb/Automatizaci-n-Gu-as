package com.calimport.guias.sap;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 *   T  Tomada      PENDIENTE y recibidaPorRepartidor (también la marcada para un
 *                  transportista externo, que queda recibida al marcarla)
 *   E  Entregada   ENTREGADA
 *   R  Rechazada   RECHAZADA
 * </pre>
 *
 * <p>{@code U_Despachador} dice quién la lleva: el transportista externo si bodega la marcó así
 * ("SAMEX"), y si no, el repartidor.
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
    public static final String CAMPO_FECHA_ENTREGA = "U_Dateguia";
    public static final String CAMPO_HORA_ENTREGA = "U_Horaguia";

    // SAP guarda fecha y hora sin zona, en la hora de la empresa (así viene DocTime), y la
    // VM corre en UTC: una entrega a las 22:00 de Santiago caería al día siguiente si se
    // tomara la zona del sistema.
    static final ZoneId ZONA_SAP = ZoneId.of("America/Santiago");
    // Un UDF de hora en SAP no lleva segundos: Service Layer los devuelve siempre en 00
    // ("12:41:00"). Se manda en ese mismo formato, ya truncado al minuto.
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Tamaños de los UDF en CALIMPORT_PRODUCTIVO, leídos de UserFieldsMD el 28-09-2026.
    // Service Layer NO avisa si un valor no cabe: responde 204 y deja ese campo en null,
    // mientras los que caben sí se guardan. Comprobado el 28-09-2026 en CALIMPORT_TEST
    // (campos de 10 y 50): la guía quedó en E, sin despachador ni foto, y la app la dio por
    // sincronizada. Como no hay error, no hay reintento que lo arregle: el dato se pierde.
    // Por eso el motivo (texto libre de hasta 500) y el nombre se recortan acá; el texto
    // completo sigue en Postgres. U_UrlFoto (100) no se recorta: una URL cortada no abre
    // nada, y la más larga posible (dominio + /api/evidencia/guia-<id>-<uuid>.webp) suma 96
    // con un id de 6 dígitos (SincronizacionSapServiceTest lo comprueba).
    // En CALIMPORT_TEST no caben ni el nombre ni la URL: ahí llegan vacíos.
    static final int LARGO_DESPACHADOR = 25;
    static final int LARGO_MOTIVO_RECHAZO = 20;

    private EstadoLogistico() {
    }

    /** El valor de {@code U_EstadoLog} que le corresponde a la guía tal como está hoy. */
    public static String codigo(Guia guia) {
        return switch (guia.getEstado()) {
            case ENTREGADA -> "E";
            case RECHAZADA -> "R";
            // Recibida: la retiró un repartidor, o bodega se la entregó a un transportista externo
            // (GuiaService la deja recibida al marcarla). Es la única fuente de la T.
            case PENDIENTE -> guia.isRecibidaPorRepartidor() ? "T" : "P";
        };
    }

    /**
     * El cuerpo del PATCH para la guía en su estado actual.
     *
     * @param nombreDespachador el repartidor que la lleva, para {@code U_Despachador}. Se manda
     *     el nombre y no el employeeId porque el UDF lo lee una persona mirando el documento
     *     en SAP, donde un número no dice nada. Si la guía tiene transportista externo, va el
     *     transportista y este parámetro no se usa.
     * @param urlFotoPublica la URL con la que la evidencia es alcanzable desde fuera de la
     *     app; null mientras la foto solo exista en el disco del servidor.
     */
    public static Map<String, Object> cuerpoPatch(Guia guia, String nombreDespachador, String urlFotoPublica) {
        // HashMap y no Map.of: los nulls son parte del contrato (limpian el campo en SAP)
        // y Map.of los rechaza.
        Map<String, Object> campos = new HashMap<>();
        String codigo = codigo(guia);
        campos.put(CAMPO_ESTADO, codigo);

        // Quien la tiene en la mano: el transportista externo si bodega la marcó así (no tiene
        // repartidor), si no el repartidor. Deja de aplicar cuando la guía vuelve a estar
        // pendiente y sin retirar: ahí se limpia para que bodega pueda reasignarla.
        String quien = guia.getTransportista() != null ? guia.getTransportista() : nombreDespachador;
        campos.put(CAMPO_DESPACHADOR, "P".equals(codigo) ? null : recortar(quien, LARGO_DESPACHADOR));

        // Los otros dos son la evidencia de cómo terminó, y solo existen en su estado.
        campos.put(CAMPO_URL_FOTO, guia.getEstado() == EstadoGuia.ENTREGADA ? urlFotoPublica : null);
        campos.put(CAMPO_MOTIVO_RECHAZO, guia.getEstado() == EstadoGuia.RECHAZADA
                ? recortar(guia.getMotivoRechazo(), LARGO_MOTIVO_RECHAZO) : null);

        return campos;
    }

    /**
     * Cuándo se entregó la guía, para {@code U_Dateguia} y {@code U_Horaguia}.
     *
     * <p>Va aparte de {@link #cuerpoPatch} porque son UDF más nuevos que los del estado y
     * no existen en todas las bases: al 05-11-2026 están en CALIMPORT_TEST pero no en
     * CALIMPORT_PRODUCTIVO, y mandarlos donde no existen hace que SAP rechace el PATCH
     * entero con 400. Quien arma el cuerpo decide si sumarlos.
     *
     * <p>Es el momento en que el repartidor la cerró en el celular ({@code fechaEntrega}),
     * no cuando el dato llegó a SAP: eso es {@code fechaFotoEnSap} y puede ser bastante
     * después si SAP estaba caído. Como la foto, solo existe en una ENTREGADA; en cualquier
     * otro estado va en null para que SAP no conserve un valor que ya no aplica.
     */
    public static Map<String, Object> camposFechaEntrega(Guia guia) {
        Map<String, Object> campos = new HashMap<>();
        ZonedDateTime entrega = guia.getEstado() == EstadoGuia.ENTREGADA && guia.getFechaEntrega() != null
                ? guia.getFechaEntrega().atZone(ZONA_SAP).truncatedTo(ChronoUnit.MINUTES)
                : null;
        campos.put(CAMPO_FECHA_ENTREGA, entrega == null ? null : entrega.toLocalDate().toString());
        campos.put(CAMPO_HORA_ENTREGA, entrega == null ? null : entrega.format(FORMATO_HORA));
        return campos;
    }

    /** Deja el texto dentro del largo del UDF; la elipsis avisa en SAP que hay más en la app. */
    static String recortar(String texto, int largo) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.length() <= largo ? limpio : limpio.substring(0, largo - 1).stripTrailing() + "…";
    }
}
