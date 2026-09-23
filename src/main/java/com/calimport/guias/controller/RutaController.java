package com.calimport.guias.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.calimport.guias.controller.dto.GenerarRutaRequest;
import com.calimport.guias.controller.dto.RutaResponse;
import com.calimport.guias.security.UsuarioActual;
import com.calimport.guias.service.RutaService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/rutas")
public class RutaController {

    private final RutaService rutaService;

    public RutaController(RutaService rutaService) {
        this.rutaService = rutaService;
    }

    /**
     * El bodeguero asigna las guías del día a un repartidor y obtiene la ruta optimizada.
     * Volver a llamarlo para el mismo repartidor y fecha reemplaza la ruta anterior.
     */
    @PostMapping
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public RutaResponse generar(@RequestBody @Valid GenerarRutaRequest request) {
        return rutaService.generar(request);
    }

    /** La de cualquier repartidor: es la vista de bodega. El repartidor usa {@code /mia}. */
    @GetMapping
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public RutaResponse obtener(
            @RequestParam Integer repartidorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return rutaService.obtener(repartidorId, fecha);
    }

    /** La ruta del repartidor que inició sesión, sin que la app tenga que conocer su id. */
    @GetMapping("/mia")
    @PreAuthorize("hasRole('Despachador')")
    public RutaResponse mia(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            Authentication authentication) {
        int employeeId = UsuarioActual.de(authentication).employeeId();
        return rutaService.obtener(employeeId, fecha != null ? fecha : LocalDate.now());
    }
}
