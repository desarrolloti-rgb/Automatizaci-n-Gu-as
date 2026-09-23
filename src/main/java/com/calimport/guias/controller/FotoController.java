package com.calimport.guias.controller;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.security.UsuarioActual;
import com.calimport.guias.service.AlmacenamientoFotos;
import com.calimport.guias.service.AlmacenamientoFotos.Foto;
import com.calimport.guias.service.AlmacenamientoFotos.FotoGuardada;
import com.calimport.guias.service.GuiaService;
import com.calimport.guias.utils.ApiException;

/**
 * Evidencia fotográfica. La entrega va en dos pasos: primero se sube la foto acá y después
 * se llama a {@code POST /api/guias/{id}/entrega} con la URL y el hash que devuelve. Así la
 * foto queda guardada aunque la señal se corte antes de confirmar la entrega, y reintentar
 * la confirmación no obliga a volver a subir varios MB.
 */
@RestController
public class FotoController {

    private final GuiaService guiaService;
    private final AlmacenamientoFotos almacenamiento;

    public FotoController(GuiaService guiaService, AlmacenamientoFotos almacenamiento) {
        this.guiaService = guiaService;
        this.almacenamiento = almacenamiento;
    }

    @PostMapping(value = "/api/guias/{id}/foto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('Despachador')")
    public FotoGuardada subir(@PathVariable Long id, @RequestParam("archivo") MultipartFile archivo,
                              Authentication authentication) {
        Guia guia = guiaService.obtenerPropia(id, UsuarioActual.de(authentication).employeeId());
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "La guía ya fue resuelta (estado " + guia.getEstado() + ")");
        }
        try {
            return almacenamiento.guardar(id, archivo.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Requiere token: son fotos de documentos firmados por clientes. Las ven los dos roles
     * (bodega revisa la evidencia, el repartidor la suya); el nombre lleva un UUID, así que
     * no se puede adivinar la foto de una guía ajena.
     */
    @GetMapping("/api/fotos/{nombre}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> ver(@PathVariable String nombre) {
        Foto foto = almacenamiento.leer(nombre)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No existe la foto " + nombre));
        return ResponseEntity.ok()
                .contentType(foto.tipo())
                // El archivo no cambia nunca (el nombre lleva un UUID): se puede cachear, pero en privado.
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePrivate())
                .body(foto.contenido());
    }
}
