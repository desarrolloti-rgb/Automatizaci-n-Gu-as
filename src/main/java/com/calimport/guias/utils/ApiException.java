package com.calimport.guias.utils;

import org.springframework.http.HttpStatus;

/**
 * Excepción de negocio genérica: se lanza con el status HTTP que corresponda
 * (400, 401, 403, 404, 409, 500, etc.) y el GlobalExceptionHandler la traduce
 * directamente a la respuesta, sin necesitar una subclase por cada código.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
