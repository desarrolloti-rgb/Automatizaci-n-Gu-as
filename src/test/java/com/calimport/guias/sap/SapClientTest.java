package com.calimport.guias.sap;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

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

    @Test
    void siSapAceptaNoTocaLaSesion() {
        when(sessionManager.executeWithSession(any())).thenReturn(null);

        new SapClient(sessionManager).actualizarEstadoLogistico(46263, Map.of("U_EstadoLog", "E"));

        verify(sessionManager, never()).descartarSesion();
        verify(sessionManager, times(1)).executeWithSession(any());
    }
}
