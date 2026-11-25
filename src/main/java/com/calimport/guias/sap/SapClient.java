package com.calimport.guias.sap;

import java.time.LocalDate;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import tools.jackson.databind.JsonNode;

/**
 * Lo que Guías necesita de SAP: el repartidor que inicia sesión, las guías por despachar, su
 * estado de última milla y el correo del cliente para el aviso de entrega.
 */
@Component
public class SapClient {

    private static final Logger log = LoggerFactory.getLogger(SapClient.class);

    private final SapSessionManager sessionManager;

    public SapClient(SapSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Guías de despacho emitidas en un rango de fechas, con solo los campos que la app
     * necesita.
     *
     * @param hasta última fecha incluida; {@code null} trae todo desde {@code desde} en
     *     adelante. Acotar por arriba sirve para reimportar un día puntual sin arrastrar
     *     todo lo emitido después.
     *
     * <p>El {@code $select} importa: sin él, SAP devuelve el documento completo con todas
     * sus líneas (cientos de campos por guía). Acotarlo baja la respuesta a una fracción.
     *
     * @param filtroExtra condición OData opcional que se suma a la fecha, para separar las
     *     guías que despacha Calimport de las que retira el cliente. Sale de configuración
     *     y no de la request: todavía no está confirmado cuál es el criterio correcto en
     *     esta instalación, así que se deja ajustable sin recompilar.
     */
    public JsonNode fetchGuiasDeDespacho(LocalDate desde, LocalDate hasta, String filtroExtra,
                                         boolean conEstadoLogistico) {
        // Se arma de una sola vez: la lambda de abajo solo puede capturar variables que no
        // se reasignan ("effectively final"), asi que un filter += aca no compila.
        //
        // DocumentStatus eq 'bost_Open' va siempre: la guia tiene que seguir abierta
        // contablemente, y una cerrada o anulada en SAP no se reparte.
        //
        // El estado logistico se pide solo si el UDF existe en esta instalacion. Pedirlo
        // cuando no existe no devuelve vacio: SAP responde 400 "Property 'U_EstadoLog' of
        // 'Document' is invalid" y la sincronizacion completa se cae. Comprobado contra el
        // Service Layer real (existe en CALIMPORT_TEST, no en CALIMPORT_PRODUCTIVO).
        //
        // Se acepta null ademas de 'P' porque en la practica los documentos llegan con el
        // campo vacio: el valor por defecto del UDF no esta aplicado, y de todas formas los
        // documentos anteriores a que se creara el campo nunca lo van a tener. Una guia que
        // esta app nunca toco esta pendiente, se llame null o 'P'. Sin esto la
        // sincronizacion trae cero guias, que es lo que paso al probarlo.
        // "hasta" es opcional y va incluido: DocDate no lleva hora (SAP la guarda siempre a
        // medianoche), asi que "le 2026-09-23" trae todas las guias de ese dia.
        String base = "DocDate ge '" + desde + "' and DocumentStatus eq 'bost_Open'";
        String porRango = hasta == null ? base : base + " and DocDate le '" + hasta + "'";
        String porFecha = conEstadoLogistico
                ? porRango + " and (U_EstadoLog eq 'P' or U_EstadoLog eq null)"
                : porRango;
        String filter = (filtroExtra == null || filtroExtra.isBlank())
                ? porFecha
                : porFecha + " and (" + filtroExtra + ")";
        // U_Despachador es un UDF, como U_EstadoLog: se pide solo si existen en esta instalacion.
        // Es donde bodega deja "SAMEX" al crear la guia en SAP.
        String campos = "DocEntry,FolioNumber,DocDate,CardName,Address,Address2,Comments,ClosingRemarks"
                + (conEstadoLogistico ? ",U_Despachador" : "");

        return sessionManager.executeWithSession(cookie ->
                sessionManager.getRestClient().get()
                        // ClosingRemarks es el pie del documento (T0.[Footer] en una consulta):
                        // ahi viene escrita la direccion de despacho real y el horario de
                        // recepcion. Es campo estandar de SAP, no un UDF, asi que pedirlo no
                        // corre el riesgo del 400 que tienen los U_*.
                        .uri("/DeliveryNotes?$filter={filter}&$select=" + campos + "&$orderby=DocEntry", filter)
                        .header("Cookie", cookie)
                        .retrieve()
                        .body(JsonNode.class));
    }

    /**
     * Guías de despacho tal como las devuelve SAP, sin filtrar ni recortar campos.
     *
     * <p>A propósito no lleva {@code $select}: la idea es ver el documento completo para
     * confirmar cómo se llaman realmente los campos en esta instalación (de dónde sale el
     * folio, con qué se filtran las guías por despachar) antes de fijar el mapeo. Cuando
     * eso esté decidido, este método se reemplaza por uno que traiga solo lo necesario.
     *
     * <p>Se ordena por DocEntry descendente para que las primeras filas sean las guías
     * más recientes, que son las útiles para revisar.
     */
    public JsonNode fetchDeliveryNotes(int top) {
        return sessionManager.executeWithSession(cookie ->
                sessionManager.getRestClient().get()
                        .uri("/DeliveryNotes?$top={top}&$orderby=DocEntry desc", String.valueOf(top))
                        .header("Cookie", cookie)
                        .retrieve()
                        .body(JsonNode.class));
    }

    /**
     * Escribe el estado logístico de una guía en SAP, que es la fuente de verdad: Postgres
     * solo amortigua la mala señal mientras el dato llega hasta acá.
     *
     * <p>Va por PATCH y no por PUT: PUT reemplazaría el documento completo y borraría todo
     * lo que no venga en el cuerpo. Acá se tocan únicamente los UDF del ciclo de última
     * milla, y el resto de la guía queda intacto.
     *
     * <p>Un valor {@code null} en el mapa se envía como null de JSON y limpia el campo en
     * SAP: es lo que necesita el reset del jefe de bodega, que devuelve una guía rechazada
     * a pendiente dejando despachador y motivo en blanco. Por eso el mapa se arma con
     * {@link java.util.HashMap} y no con {@code Map.of}, que no admite nulls.
     *
     * <p>Service Layer responde 204 sin cuerpo, así que no hay nada que deserializar.
     *
     * <p>Ante un 400 se reintenta <b>una vez con una sesión nueva</b>. Una sesión puede
     * quedar inservible para escribir sin que SAP responda 401: el 28-09-2026, después de
     * que cambiaron la definición de los UDF en TEST, la sesión abierta de la app recibía
     * "Could not commit transaction: Error -1" (-1116) en cada PATCH, mientras el mismo
     * cuerpo exacto pasaba con cualquier sesión nueva. Como solo se renovaba ante 401, la
     * guía quedó trabada hasta reiniciar la app. El PATCH manda el estado completo, así que
     * repetirlo es inofensivo; si con sesión nueva también falla, el error es de los datos
     * y se propaga para que quede para el reintento.
     */
    public void actualizarEstadoLogistico(int docEntry, Map<String, Object> campos) {
        try {
            sessionManager.executeWithSession(cookie -> patchEstado(docEntry, campos, cookie));
        } catch (HttpClientErrorException.BadRequest e) {
            log.warn("SAP rechazo el estado de DocEntry {} (400); se reintenta una vez con sesion nueva", docEntry);
            sessionManager.descartarSesion();
            sessionManager.executeWithSession(cookie -> patchEstado(docEntry, campos, cookie));
        }
    }

    private Void patchEstado(int docEntry, Map<String, Object> campos, String cookie) {
        sessionManager.getRestClient()
                .patch()
                .uri("/DeliveryNotes({docEntry})", docEntry)
                .header("Cookie", cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .body(campos)
                .retrieve()
                .toBodilessEntity();
        return null;
    }

    /**
     * El cliente ({@code CardCode}) de una guía, para el correo de despacho. Se pide al
     * enviar y no se guarda en la sincronización: así sirve también para las guías que ya
     * estaban importadas cuando nació el correo.
     *
     * <p>Con {@code $filter} y no {@code DeliveryNotes(docEntry)}: una guía que no está en
     * SAP (las creadas a mano en la app) da un arreglo vacío en vez de un 404.
     */
    public JsonNode fetchClienteDeGuia(int docEntry) {
        String filter = "DocEntry eq " + docEntry;
        return sessionManager.executeWithSession(cookie ->
                sessionManager.getRestClient().get()
                        .uri("/DeliveryNotes?$filter={filter}&$select=DocEntry,CardCode", filter)
                        .header("Cookie", cookie)
                        .retrieve()
                        .body(JsonNode.class));
    }

    /**
     * El correo de la ficha del cliente ({@code EmailAddress}), el mismo que usa la app de
     * HES para pedirle la HES. Puede traer varios separados por {@code ;} o coma.
     */
    public JsonNode fetchCorreoDelCliente(String cardCode) {
        String filter = "CardCode eq '" + (cardCode == null ? "" : cardCode.replace("'", "''")) + "'";
        return sessionManager.executeWithSession(cookie ->
                sessionManager.getRestClient().get()
                        .uri("/BusinessPartners?$filter={filter}&$select=CardCode,EmailAddress", filter)
                        .header("Cookie", cookie)
                        .retrieve()
                        .body(JsonNode.class));
    }

    /** Igual que en Dashboard: busca en EmployeesInfo por eMail, solo activos. */
    public JsonNode queryEmployeeByEmail(String email) {
        String escapedEmail = email == null ? "" : email.replace("'", "''");
        String filter = "eMail eq '" + escapedEmail + "' and Active eq 'tYES'";
        return sessionManager.executeWithSession(cookie ->
                sessionManager.getRestClient().get()
                        .uri("/EmployeesInfo?$filter={filter}", filter)
                        .header("Cookie", cookie)
                        .retrieve()
                        .body(JsonNode.class));
    }
}
