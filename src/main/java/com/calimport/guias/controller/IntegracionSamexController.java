package com.calimport.guias.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calimport.guias.service.EnviosExternosService;
import com.calimport.guias.service.EnviosExternosService.EvidenciaGuardada;
import com.calimport.guias.service.EnviosExternosService.Pendiente;
import com.calimport.guias.service.EnviosExternosService.ResultadoBusqueda;

/**
 * Lo que usa el lector del portal de Samex ({@code samex-sync}, Cloud Run Job): pide las guías que
 * tiene que buscar y devuelve lo que encontró. Lo autoriza {@code IntegracionesSecurityConfig}
 * con el ID token de Google de su cuenta de servicio, no con el JWT de la app.
 */
@RestController
@RequestMapping("/api/integraciones/samex")
public class IntegracionSamexController {

    /** Como en {@code guias.transportistas}: es el valor que bodega elige en la app. */
    static final String SAMEX = "SAMEX";

    private final EnviosExternosService envios;

    public IntegracionSamexController(EnviosExternosService envios) {
        this.envios = envios;
    }

    /** Los PDF de entregas que el lector tiene que bajar del portal y subir. */
    public record RespuestaRegistro(List<String> necesitanEvidencia) {
    }

    @GetMapping("/pendientes")
    @PreAuthorize("hasRole('INTEGRACION')")
    public List<Pendiente> pendientes() {
        return envios.pendientes(SAMEX);
    }

    @PostMapping("/envios")
    @PreAuthorize("hasRole('INTEGRACION')")
    public RespuestaRegistro registrar(@RequestBody List<ResultadoBusqueda> resultados) {
        return new RespuestaRegistro(envios.registrar(SAMEX, resultados));
    }

    /**
     * El PDF de la entrega (el albarán que baja el portal), como cuerpo de la petición. Va crudo y
     * no como multipart: es un solo archivo, y así el tope lo pone {@code AlmacenamientoFotos} y no
     * la configuración de multipart.
     */
    @PutMapping(value = "/envios/{numero}/evidencia", consumes = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('INTEGRACION')")
    public EvidenciaGuardada subirEvidencia(@PathVariable String numero, @RequestBody byte[] pdf) {
        return envios.guardarEvidencia(SAMEX, numero, pdf);
    }
}
