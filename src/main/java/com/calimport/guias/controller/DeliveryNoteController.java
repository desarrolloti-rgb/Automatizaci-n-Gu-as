package com.calimport.guias.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.calimport.guias.sap.SapClient;
import com.calimport.guias.sap.SapSessionManager.SapUnauthorizedException;
import com.calimport.guias.utils.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Ventana de solo lectura a las guías de despacho de SAP, para inspeccionar el documento
 * real antes de fijar el mapeo a {@link com.calimport.guias.model.Guia}.
 *
 * <p>Devuelve el JSON de Service Layer sin transformar: es justamente lo que permite ver
 * cómo se llaman los campos en esta instalación. No es el endpoint que va a usar la app —
 * cuando el mapeo esté decidido, la sincronización tendrá el suyo, con un DTO propio.
 */
@RestController
@RequestMapping("/api/sap")
@PreAuthorize("hasRole('JEFE_BODEGA')")
public class DeliveryNoteController {

    private static final Logger log = LoggerFactory.getLogger(DeliveryNoteController.class);

    /**
     * Un documento de SAP sin recortar es grande (trae todas las líneas). Sin tope, un
     * $top alto colgaría la request y la sesión de SAP: para inspeccionar bastan pocas.
     */
    private static final int TOP_MAXIMO = 20;

    private final SapClient sapClient;

    public DeliveryNoteController(SapClient sapClient) {
        this.sapClient = sapClient;
    }

    @GetMapping("/delivery-notes")
    public JsonNode deliveryNotes(@RequestParam(defaultValue = "1") int top) {
        if (top < 1 || top > TOP_MAXIMO) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "top debe estar entre 1 y " + TOP_MAXIMO);
        }

        try {
            return sapClient.fetchDeliveryNotes(top);
        } catch (SapUnauthorizedException e) {
            log.error("Sesion SAP invalida consultando guias: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error de sesión con SAP");
        } catch (Exception e) {
            log.error("Error consultando guias de despacho en SAP: {}", e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error de conexión con SAP");
        }
    }
}
