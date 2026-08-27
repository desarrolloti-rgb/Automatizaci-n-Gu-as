package com.calimport.guias.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.calimport.guias.model.Repartidor;
import com.calimport.guias.service.RepartidorService;

@RestController
@RequestMapping("/api/repartidores")
@PreAuthorize("isAuthenticated()")
public class RepartidorController {

    private final RepartidorService repartidorService;

    public RepartidorController(RepartidorService repartidorService) {
        this.repartidorService = repartidorService;
    }

    /** Sin filtro trae todos; con ?activos=true, solo los que reciben asignaciones. */
    @GetMapping
    public List<Repartidor> listar(@RequestParam(required = false) Boolean activos) {
        if (Boolean.TRUE.equals(activos)) {
            return repartidorService.listarActivos();
        }
        return repartidorService.listar();
    }

    @GetMapping("/{id}")
    public Repartidor obtenerPorId(@PathVariable int id) {
        return repartidorService.obtenerPorId(id);
    }
}
