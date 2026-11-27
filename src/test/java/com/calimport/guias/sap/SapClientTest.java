package com.calimport.guias.sap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El reintento con sesión nueva del PATCH de estado. La llamada HTTP en sí queda dentro de
 * {@code executeWithSession}, que acá se simula: lo que se prueba es qué se hace cuando SAP
 * la rechaza.
 */
@ExtendWith(MockitoExtension.class)
class SapClientTest {

    @Mock
    private SapSessionManager sessionManager;

    private static HttpClientErrorException badRequest() {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null,
                "{\"error\":{\"code\":-1116}}".getBytes(), null);
    }

    @Test
    void anteUn400DescartaLaSesionYReintentaUnaVez() {
        // Caso real: la sesión abierta quedó inservible y cualquier sesión nueva funcionaba.
        when(sessionManager.executeWithSession(any())).thenThrow(badRequest()).thenReturn(null);

        new SapClient(sessionManager).actualizarEstadoLogistico(46263, Map.of("U_EstadoLog", "E"));

        InOrder orden = inOrder(sessionManager);
        orden.verify(sessionManager).executeWithSession(any());
        orden.verify(sessionManager).descartarSesion();
        orden.verify(sessionManager).executeWithSession(any());
    }

    @Test
    void siConSesionNuevaTambienFallaSePropagaParaElReintento() {
        // Entonces el problema son los datos, no la sesión: no se insiste más acá.
        when(sessionManager.executeWithSession(any())).thenThrow(badRequest());

        assertThrows(HttpClientErrorException.BadRequest.class,
                () -> new SapClient(sessionManager).actualizarEstadoLogistico(46263, Map.of("U_EstadoLog", "E")));

        verify(sessionManager, times(2)).executeWithSession(any());
    }

    private static JsonNode pagina(boolean conSiguiente, int... ids) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode pagina = json.createObjectNode();
        ArrayNode value = pagina.putArray("value");
        for (int id : ids) {
            value.addObject().put("EmployeeID", id).put("eMail", "e" + id + "@calimport.cl").put("Active", "tYES");
        }
        if (conSiguiente) {
            pagina.put("odata.nextLink", "EmployeesInfo?$skip=" + ids.length);
        }
        return pagina;
    }

    @Test
    void losEmpleadosSeLeenPaginaPorPagina() {
        // Service Layer entrega 20 por página por defecto: sin seguir el nextLink se perderían.
        when(sessionManager.executeWithSession(any())).thenReturn(pagina(true, 1, 2), pagina(false, 3));

        List<EmpleadoSap> empleados = new SapClient(sessionManager).fetchEmpleados();

        assertEquals(List.of(1, 2, 3), empleados.stream().map(EmpleadoSap::employeeId).toList());
        verify(sessionManager, times(2)).executeWithSession(any());
    }

    @Test
    void unaRespuestaSinValueEsUnError() {
        // Tomarla como "cero empleados" daría de baja a todos los repartidores.
        when(sessionManager.executeWithSession(any())).thenReturn(new ObjectMapper().createObjectNode());

        assertThrows(IllegalStateException.class, () -> new SapClient(sessionManager).fetchEmpleados());
    }

    @Test
    void siSapAceptaNoTocaLaSesion() {
        when(sessionManager.executeWithSession(any())).thenReturn(null);

        new SapClient(sessionManager).actualizarEstadoLogistico(46263, Map.of("U_EstadoLog", "E"));

        verify(sessionManager, never()).descartarSesion();
        verify(sessionManager, times(1)).executeWithSession(any());
    }
}
