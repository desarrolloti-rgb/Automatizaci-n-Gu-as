package com.calimport.guias.controller;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
import com.calimport.guias.controller.dto.DefinirDireccionRequest;
import com.calimport.guias.controller.dto.DefinirHorarioRequest;
import com.calimport.guias.controller.dto.EntregaRequest;
import com.calimport.guias.controller.dto.RechazoRequest;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.sap.SapSessionManager.SapUnauthorizedException;
import com.calimport.guias.security.UsuarioActual;
import com.calimport.guias.service.GuiaService;
import com.calimport.guias.service.GuiaSyncService;
import com.calimport.guias.utils.ApiException;

import jakarta.validation.Valid;

/**
 * Cada método declara quién puede llamarlo. Bodega (JEFE_BODEGA) importa, asigna y define
 * horarios; el repartidor resuelve sus guías. Que la guía sea del repartidor que llama lo
 * valida {@link GuiaService}, no el rol: el rol solo dice qué tipo de operación puede hacer.
 */
@RestController
@RequestMapping("/api/guias")
public class GuiaController {

    private static final Logger log = LoggerFactory.getLogger(GuiaController.class);

    private final GuiaService guiaService;
    private final GuiaSyncService guiaSyncService;

    public GuiaController(GuiaService guiaService, GuiaSyncService guiaSyncService) {
        this.guiaService = guiaService;
        this.guiaSyncService = guiaSyncService;
    }

    /**
     * Trae de SAP las guías emitidas desde una fecha y las vuelca a la copia local.
     *
     * <p>Es idempotente: repetirla no duplica nada y solo refresca las que sigan
     * PENDIENTE. Por ahora se dispara a mano; cuando el criterio de filtrado esté
     * confirmado, se le puede colgar un {@code @Scheduled}.
     */
    @PostMapping("/sincronizar")
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public GuiaSyncService.Resultado sincronizar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        LocalDate fecha = desde != null ? desde : LocalDate.now();
        // Se valida acá y no en el servicio porque es un error de quien llama, no del
        // negocio: un rango al revés no trae cero guías, trae una consulta sin sentido.
        if (hasta != null && hasta.isBefore(fecha)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La fecha 'hasta' no puede ser anterior a 'desde'");
        }
        try {
            return guiaSyncService.sincronizar(fecha, hasta);
        } catch (SapUnauthorizedException e) {
            log.error("Sesion SAP invalida sincronizando guias: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error de sesión con SAP");
        } catch (Exception e) {
            log.error("Error sincronizando guias desde SAP: {}", e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error de conexión con SAP");
        }
    }

    /**
     * Bodega ve todas (filtrables por estado o repartidor). Un repartidor ve solo las suyas:
     * el filtro sale de su token, no del query param.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<Guia> listar(
            @RequestParam(required = false) EstadoGuia estado,
            @RequestParam(required = false) Integer repartidorId,
            Authentication authentication) {
        return guiaService.listarPara(UsuarioActual.de(authentication), estado, repartidorId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Guia obtenerPorId(@PathVariable Long id, Authentication authentication) {
        return guiaService.obtenerPara(id, UsuarioActual.de(authentication));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public Guia crear(@RequestBody @Valid CrearGuiaRequest request) {
        return guiaService.crear(request.docEntry(), request.folio(), request.cliente(), request.direccion());
    }

    @PatchMapping("/{id}/repartidor")
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public Guia asignarRepartidor(@PathVariable Long id, @RequestBody AsignarRepartidorRequest request) {
        return guiaService.asignarRepartidor(id, request.repartidorId());
    }

    /** El bodeguero fija o corrige el horario de recepción. Manda sobre el comentario de SAP. */
    @PatchMapping("/{id}/horario")
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public Guia definirHorario(@PathVariable Long id, @RequestBody DefinirHorarioRequest request) {
        return guiaService.definirHorario(id, request.ventanaDesde(), request.ventanaHasta(), request.nota());
    }

    /**
     * El bodeguero corrige la dirección de despacho, leyendo el pie del documento. Manda
     * sobre SAP: ninguna sincronización la vuelve a pisar.
     */
    @PatchMapping("/{id}/direccion")
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public Guia definirDireccion(@PathVariable Long id, @RequestBody DefinirDireccionRequest request) {
        return guiaService.definirDireccion(id, request.direccion());
    }

    @PatchMapping("/{id}/recepcion")
    @PreAuthorize("hasRole('Despachador')")
    public Guia marcarRecibidaPorRepartidor(@PathVariable Long id, Authentication authentication) {
        return guiaService.marcarRecibidaPorRepartidor(id, UsuarioActual.de(authentication).employeeId());
    }

    @PostMapping("/{id}/entrega")
    @PreAuthorize("hasRole('Despachador')")
    public Guia entregar(@PathVariable Long id, @RequestBody EntregaRequest request, Authentication authentication) {
        return guiaService.entregar(id, UsuarioActual.de(authentication).employeeId(),
                request.urlFoto(), request.hashFoto());
    }

    /** El motivo viaja en el cuerpo y es obligatorio: ver {@link RechazoRequest}. */
    @PostMapping("/{id}/rechazo")
    @PreAuthorize("hasRole('Despachador')")
    public Guia rechazar(@PathVariable Long id, @RequestBody @Valid RechazoRequest request,
                         Authentication authentication) {
        return guiaService.rechazar(id, UsuarioActual.de(authentication).employeeId(), request.motivo());
    }

    /**
     * Devuelve a PENDIENTE una guía rechazada, para reintentar el despacho. En SAP es el
     * flujo R → P. Solo bodega: no es deshacer el rechazo del repartidor, es decidir que se
     * vuelve a intentar.
     */
    @PatchMapping("/{id}/reapertura")
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public Guia reabrir(@PathVariable Long id) {
        return guiaService.reabrir(id);
    }

    /**
     * Fuerza el envío a SAP de una guía que quedó pendiente de sincronizar. Es de bodega y
     * no del repartidor: es una acción de operación sobre la integración, no del reparto.
     */
    @PatchMapping("/{id}/sincronizada")
    @PreAuthorize("hasRole('JEFE_BODEGA')")
    public Guia reintentarSincronizacion(@PathVariable Long id) {
        return guiaService.reintentarSincronizacion(id);
    }
}
