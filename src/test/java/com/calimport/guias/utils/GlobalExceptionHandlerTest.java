package com.calimport.guias.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void cadaApiExceptionSaleConSuPropioStatus() {
        // Es la razón de tener una sola clase de excepción: el status viaja en el objeto.
        assertEquals(HttpStatus.BAD_REQUEST, statusDe(HttpStatus.BAD_REQUEST));
        assertEquals(HttpStatus.UNAUTHORIZED, statusDe(HttpStatus.UNAUTHORIZED));
        assertEquals(HttpStatus.FORBIDDEN, statusDe(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.NOT_FOUND, statusDe(HttpStatus.NOT_FOUND));
        assertEquals(HttpStatus.CONFLICT, statusDe(HttpStatus.CONFLICT));
        assertEquals(HttpStatus.BAD_GATEWAY, statusDe(HttpStatus.BAD_GATEWAY));
    }

    private HttpStatus statusDe(HttpStatus status) {
        ResponseEntity<ProblemDetail> respuesta = handler.handleApiException(new ApiException(status, "mensaje"));
        return HttpStatus.valueOf(respuesta.getStatusCode().value());
    }

    @Test
    void elMensajeDeNegocioLlegaAlClienteEnElProblemDetail() {
        ResponseEntity<ProblemDetail> respuesta = handler.handleApiException(
                new ApiException(HttpStatus.CONFLICT, "La guía ya fue resuelta (estado ENTREGADA)"));

        ProblemDetail cuerpo = respuesta.getBody();
        assertNotNull(cuerpo);
        assertEquals("La guía ya fue resuelta (estado ENTREGADA)", cuerpo.getDetail());
        assertEquals(409, cuerpo.getStatus());
    }

    @Test
    void unaExcepcionInesperadaNoFiltraElMensajeInternoAlCliente() {
        ResponseEntity<ProblemDetail> respuesta =
                handler.handleUnexpected(new IllegalStateException("jdbc://user:password@host falló"));

        ProblemDetail cuerpo = respuesta.getBody();
        assertNotNull(cuerpo);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), respuesta.getStatusCode().value());
        assertEquals("Error interno del servidor", cuerpo.getDetail());
    }

    @Test
    void unApiExceptionDe500TambienSeGeneralizaAlCliente() {
        // El status lo decide la excepción, pero el mensaje sigue siendo el que se le puso:
        // por eso los 500 internos deben lanzarse sin datos sensibles en el mensaje.
        ResponseEntity<ProblemDetail> respuesta = handler.handleApiException(
                new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor"));

        assertEquals(500, respuesta.getStatusCode().value());
        assertEquals("Error interno del servidor", respuesta.getBody().getDetail());
    }
}
