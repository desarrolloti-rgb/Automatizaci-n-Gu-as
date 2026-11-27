package com.calimport.guias.controller.dto;

import com.calimport.guias.service.GuiaSyncService;
import com.calimport.guias.service.RepartidorService.CambiosRepartidores;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lo que respondió "Sincronizar desde SAP": las guías importadas y los cambios en la lista de
 * repartidores.
 *
 * @param repartidores null si no se sincronizaron (perfil local, o falló la lectura).
 * @param errorRepartidores por qué no se pudo poner al día la lista; null si no hubo error.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultadoSincronizacion(int sincronizadas, int descartadas, int retirosEnLocal,
                                      int porTransportistaExterno, CambiosRepartidores repartidores,
                                      String errorRepartidores) {

    public static ResultadoSincronizacion de(GuiaSyncService.Resultado guias, CambiosRepartidores repartidores,
                                             String errorRepartidores) {
        return new ResultadoSincronizacion(guias.sincronizadas(), guias.descartadas(), guias.retirosEnLocal(),
                guias.porTransportistaExterno(), repartidores, errorRepartidores);
    }
}
