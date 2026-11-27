package com.calimport.guias.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.calimport.guias.sap.SapClient;
import com.calimport.guias.service.RepartidorService.CambiosRepartidores;

/**
 * Pone al día la lista de repartidores con los empleados de SAP. Corre junto con la
 * sincronización de guías que dispara bodega: es cuando va a asignar y necesita ver a todos
 * los despachadores, también a los que nunca iniciaron sesión.
 */
@Service
public class SincronizacionRepartidoresService {

    private final SapClient sapClient;
    private final RepartidorService repartidorService;
    private final boolean usuariosDeSap;

    /**
     * @param authModo con {@code local} los usuarios salen de la configuración y no de SAP:
     *     sincronizar daría de baja a los de prueba, que SAP no conoce.
     */
    public SincronizacionRepartidoresService(SapClient sapClient, RepartidorService repartidorService,
                                             @Value("${auth.modo:sap}") String authModo) {
        this.sapClient = sapClient;
        this.repartidorService = repartidorService;
        this.usuariosDeSap = !"local".equals(authModo);
    }

    /** Vacío si los usuarios no salen de SAP. Un error al leer SAP se propaga. */
    public Optional<CambiosRepartidores> sincronizar() {
        if (!usuariosDeSap) {
            return Optional.empty();
        }
        return Optional.of(repartidorService.sincronizarConSap(sapClient.fetchEmpleados()));
    }
}
