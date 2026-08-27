package com.calimport.guias.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.calimport.guias.controller.dto.AsignarRepartidorRequest;
import com.calimport.guias.controller.dto.CrearGuiaRequest;
import com.calimport.guias.controller.dto.EntregaRequest;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.service.GuiaService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/guias")
@PreAuthorize("isAuthenticated()")
public class GuiaController {

    private final GuiaService guiaService;

    public GuiaController(GuiaService guiaService) {
        this.guiaService = guiaService;
    }

    /** Sin filtros trae todas las guías; con estado o repartidorId, las filtra. */
    @GetMapping
    public List<Guia> listar(
            @RequestParam(required = false) EstadoGuia estado,
            @RequestParam(required = false) Integer repartidorId) {
        if (estado != null) {
            return guiaService.listarPorEstado(estado);
        }
        if (repartidorId != null) {
            return guiaService.listarPorRepartidor(repartidorId);
        }
        return guiaService.listar();
    }

    @GetMapping("/{id}")
    public Guia obtenerPorId(@PathVariable Long id) {
        return guiaService.obtenerPorId(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Guia crear(@RequestBody @Valid CrearGuiaRequest request) {
        return guiaService.crear(request.docEntry(), request.folio(), request.cliente(), request.direccion());
    }

    @PatchMapping("/{id}/repartidor")
    public Guia asignarRepartidor(@PathVariable Long id, @RequestBody AsignarRepartidorRequest request) {
        return guiaService.asignarRepartidor(id, request.repartidorId());
    }

    @PatchMapping("/{id}/recepcion")
    public Guia marcarRecibidaPorRepartidor(@PathVariable Long id) {
        return guiaService.marcarRecibidaPorRepartidor(id);
    }

    @PostMapping("/{id}/entrega")
    public Guia entregar(@PathVariable Long id, @RequestBody EntregaRequest request) {
        return guiaService.entregar(id, request.urlFoto(), request.hashFoto());
    }

    @PostMapping("/{id}/rechazo")
    public Guia rechazar(@PathVariable Long id) {
        return guiaService.rechazar(id);
    }

    @PatchMapping("/{id}/sincronizada")
    public Guia marcarSincronizada(@PathVariable Long id) {
        return guiaService.marcarSincronizada(id);
    }
}
