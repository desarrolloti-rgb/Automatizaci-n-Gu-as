package com.calimport.guias.sap;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Solo lo que Guias necesita de SAP por ahora: resolver al repartidor que inicia sesión. */
@Component
public class SapClient {

    private final SapSessionManager sessionManager;

    public SapClient(SapSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Guías de despacho emitidas desde una fecha, con solo los cuatro campos que la app
     * necesita.
     *
     * <p>El {@code $select} importa: sin él, SAP devuelve el documento completo con todas
     * sus líneas (cientos de campos por guía). Acotarlo baja la respuesta a una fracción.
     *
     * @param filtroExtra condición OData opcional que se suma a la fecha, para separar las
     *     guías que despacha Calimport de las que retira el cliente. Sale de configuración
     *     y no de la request: todavía no está confirmado cuál es el criterio correcto en
     *     esta instalación, así que se deja ajustable sin recompilar.
     */
    public JsonNode fetchGuiasDeDespacho(LocalDate desde, String filtroExtra) {
        // Se arma de una sola vez: la lambda de abajo solo puede capturar variables que no
        // se reasignan ("effectively final"), asi que un filter += aca no compila.
        //
        // Ademas de la fecha se piden dos condiciones del ciclo de ultima milla:
        //  - U_EstadoLog eq 'P': solo las que nadie tomo todavia. Las que ya estan en T, E
        //    o R salieron de esta app y ya viven en Postgres; volver a importarlas no
        //    aporta y arriesga pisar lo que el repartidor hizo en terreno.
        //  - DocumentStatus eq 'bost_Open': la guia sigue abierta contablemente. Una
        //    cerrada o anulada en SAP no se reparte.
        String porFecha = "DocDate ge '" + desde + "'"
                + " and U_EstadoLog eq 'P' and DocumentStatus eq 'bost_Open'";
        String filter = (filtroExtra == null || filtroExtra.isBlank())
                ? porFecha
                : porFecha + " and (" + filtroExtra + ")";

        return sessionManager.executeWithSession(cookie ->
                sessionManager.getRestClient().get()
                        .uri("/DeliveryNotes?$filter={filter}"
                           + "&$select=DocEntry,FolioNumber,CardName,Address,Address2,Comments"
                           + "&$orderby=DocEntry", filter)
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
     */
    public void actualizarEstadoLogistico(int docEntry, Map<String, Object> campos) {
        sessionManager.executeWithSession(cookie -> {
            sessionManager.getRestClient()
                    .patch()
                    .uri("/DeliveryNotes({docEntry})", docEntry)
                    .header("Cookie", cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(campos)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
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
