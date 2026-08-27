package com.calimport.guias.sap;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Solo lo que Guias necesita de SAP por ahora: resolver al repartidor que inicia sesión. */
@Component
public class SapClient {

    private final SapSessionManager sessionManager;

    public SapClient(SapSessionManager sessionManager) {
        this.sessionManager = sessionManager;
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
