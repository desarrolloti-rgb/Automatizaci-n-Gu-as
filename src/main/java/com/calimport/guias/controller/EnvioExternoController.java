package com.calimport.guias.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.calimport.guias.controller.dto.FijarEnvioRequest;
import com.calimport.guias.model.EnvioExterno;
import com.calimport.guias.service.EnviosExternosService;
import com.calimport.guias.service.EnviosExternosService.VistaBodega;
import com.calimport.guias.service.GuiaService;

/**
 * Lo que bodega ve y hace de las guías que lleva un transportista externo: en qué va cada una
 * según su portal, cuáles hay que revisar, y fijar a mano el número de envío cuando la búsqueda
 * por folio no la encuentra. Todo es del jefe de bodega; el repartidor no ve guías Samex.
 */
@RestController
public class EnvioExternoController {

    private final EnviosExternosService envios;
    private final GuiaService guiaService;

    public EnvioExternoController(EnviosExternosService envios, GuiaService guiaService) {
        this.envios = envios;
        this.guiaService = guiaService;
    }

    /**
     * El seguimiento de todas las guías Samex (también las ya entregadas, para mostrar cuándo y con
     * qué envío), la última lectura del portal y cuántas quedan por buscar. Va todo en una llamada:
     * la tabla de bodega lo cruza con las guías por {@code guiaId}.
     */
    @GetMapping("/api/envios-externos")
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public VistaBodega envios() {
        return envios.vistaBodega(IntegracionSamexController.SAMEX, Instant.now());
    }

    @PatchMapping("/api/guias/{id}/envio-externo")
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public EnvioExterno fijarNumero(@PathVariable Long id, @RequestBody FijarEnvioRequest request) {
        return envios.fijarNumero(id, request.numero());
    }

    /** Los que se pueden elegir en el selector de la tabla, junto a los repartidores. */
    @GetMapping("/api/transportistas")
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public List<String> transportistas() {
        return guiaService.transportistas();
    }
}
