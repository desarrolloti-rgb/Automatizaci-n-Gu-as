package com.calimport.guias.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Reemplaza los try/catch repetidos en cada controller: cualquier excepción termina
 * acá y se traduce a un ProblemDetail con el status que le corresponde, sin filtrar
 * detalles internos ni stacktraces al cliente.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("Error procesando la request", e);
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Datos inválidos");
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detalle);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    /** JSON mal formado o con tipos que no calzan: es un error del cliente, no un 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleCuerpoIlegible(HttpMessageNotReadableException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "El cuerpo de la request no es un JSON válido para esta operación");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleArchivoGrande(MaxUploadSizeExceededException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "La foto supera el tamaño máximo permitido (10 MB)");
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(pd);
    }

    /** Sin esto, una subida sin el campo "archivo" terminaría como 500. */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ProblemDetail> handleMultipartInvalido(Exception e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Se esperaba la foto en el campo 'archivo' (multipart/form-data)");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    /**
     * Un {@code @PreAuthorize} que no pasa. Sin esto caería en el handler genérico y el
     * cliente vería un 500 donde corresponde "no tenés permiso". El 401 (sin token o token
     * vencido) no pasa por acá: lo resuelve el entry point de SecurityConfig.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleSinPermiso(AccessDeniedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "No tiene permisos para esta operación");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
    }

    /**
     * Una URL que no existe. Sin esto caía en el handler genérico: el cliente recibía un 500
     * y cada sondeo de un robot escribía un ERROR con stacktrace en el log, ahogando los
     * errores de verdad. Un recurso inexistente es 404 y no merece traza.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleRecursoInexistente(NoResourceFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No existe el recurso solicitado");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Error no manejado procesando la request", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }
}
