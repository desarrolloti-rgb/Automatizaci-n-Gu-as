package com.calimport.guias.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.Guia;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.repository.RepartidorRepository;
import com.calimport.guias.sap.EstadoLogistico;
import com.calimport.guias.sap.SapClient;

/**
 * Lleva a SAP el estado logístico de las guías que se resolvieron en la app.
 *
 * <p>SAP es la fuente de verdad del ciclo, pero el repartidor trabaja en la calle con mala
 * señal y el Service Layer no siempre responde. Si cada entrega dependiera de que SAP
 * conteste en ese instante, una caída de red dejaría al repartidor sin poder cerrar la
 * guía que ya entregó. Por eso el orden es: <b>primero se guarda en Postgres</b>, que es
 * lo que no puede perderse, y después se empuja a SAP.
 *
 * <p>Si el empuje falla, la guía queda con {@code sincronizada = false} y
 * {@link #reintentarPendientes()} lo vuelve a intentar cada pocos minutos. El repartidor
 * nunca se entera: para él la entrega quedó registrada, porque efectivamente quedó.
 *
 * <p>El PATCH es idempotente —manda el estado completo, no un incremento—, así que
 * reintentarlo de más no hace daño. Esa es la razón de reintentar sin llevar la cuenta de
 * intentos: volver a enviar el mismo estado vuelve a dejarlo igual.
 */
@Service
public class SincronizacionSapService {

    private static final Logger log = LoggerFactory.getLogger(SincronizacionSapService.class);

    private final SapClient sapClient;
    private final GuiaRepository guiaRepository;
    private final RepartidorRepository repartidorRepository;
    private final String urlPublica;
    private final boolean udfDisponible;

    public SincronizacionSapService(SapClient sapClient, GuiaRepository guiaRepository,
                                    RepartidorRepository repartidorRepository,
                                    @Value("${guias.url-publica:}") String urlPublica,
                                    @Value("${guias.sap.udf-estado-logistico:false}") boolean udfDisponible) {
        this.sapClient = sapClient;
        this.guiaRepository = guiaRepository;
        this.repartidorRepository = repartidorRepository;
        this.urlPublica = urlPublica == null ? "" : urlPublica.trim().replaceAll("/+$", "");
        this.udfDisponible = udfDisponible;
    }

    /**
     * Intenta reflejar la guía en SAP y deja registrado si lo logró.
     *
     * <p>No propaga la excepción a propósito: quien la llama ya guardó el cambio en
     * Postgres y su operación fue exitosa. Que SAP no conteste es un problema de
     * sincronización, no de la entrega.
     */
    @Transactional
    public void empujar(Guia guia) {
        // Sin los UDF creados en SAP, el PATCH no falla en silencio: responde 400
        // "Property 'U_EstadoLog' of 'Document' is invalid". Mientras no existan, la app
        // funciona igual contra Postgres y no ensucia el log con un error por cada entrega.
        if (!udfDisponible) {
            return;
        }
        try {
            sapClient.actualizarEstadoLogistico(guia.getDocEntry(), cuerpoPara(guia));
            guia.setSincronizada(true);
            // La foto recién existe para el resto de la empresa cuando llega a SAP: ése es
            // el momento en que la guía queda despachada de cara al resto de la empresa, y
            // no cuando el repartidor cerró la guía en su celular sin señal. Se escribe una
            // sola vez: un reintento no cambia cuándo llegó.
            if (guia.getUrlFoto() != null && guia.getFechaFotoEnSap() == null) {
                guia.setFechaFotoEnSap(Instant.now());
            }
            guiaRepository.save(guia);
            log.debug("Guia folio {} reflejada en SAP como {}", guia.getFolio(), EstadoLogistico.codigo(guia));
        } catch (RuntimeException e) {
            guia.setSincronizada(false);
            guiaRepository.save(guia);
            log.warn("No se pudo reflejar en SAP la guia folio {} (queda para reintento): {}",
                    guia.getFolio(), e.getMessage());
        }
    }

    /**
     * Reintenta las que quedaron sin llegar a SAP.
     *
     * <p>Cada dos minutos y no más seguido: si SAP está caído, insistir cada pocos segundos
     * no lo arregla y solo llena el log. Una guía recién importada nace con
     * {@code sincronizada = true} porque SAP ya la tiene como pendiente, así que acá solo
     * caen las que la app efectivamente cambió.
     */
    @Scheduled(fixedDelayString = "${guias.sync.reintento-ms:120000}", initialDelayString = "60000")
    public void reintentarPendientes() {
        if (!udfDisponible) {
            return;
        }
        List<Guia> pendientes = guiaRepository.findBySincronizadaFalse();
        if (pendientes.isEmpty()) {
            return;
        }
        log.info("Reintentando sincronizar {} guias con SAP", pendientes.size());
        for (Guia guia : pendientes) {
            empujar(guia);
        }
    }

    private Map<String, Object> cuerpoPara(Guia guia) {
        return EstadoLogistico.cuerpoPatch(guia, nombreDespachador(guia), urlFotoPublica(guia));
    }

    private String nombreDespachador(Guia guia) {
        if (guia.getRepartidorId() == null) {
            return null;
        }
        return repartidorRepository.findById(guia.getRepartidorId())
                .map(r -> r.getNombre())
                // Si la copia local no lo tiene, el id igual identifica: es preferible a
                // dejar el campo vacio y que en SAP no se sepa quien la llevaba.
                .orElseGet(() -> String.valueOf(guia.getRepartidorId()));
    }

    /**
     * La foto la sirve esta misma app y su URL es relativa ({@code /api/fotos/...}). En SAP
     * la mira alguien desde afuera, así que se le antepone el dominio público. Sin
     * {@code guias.url-publica} configurada se manda la ruta relativa: sirve para rastrear
     * el archivo aunque no se pueda abrir con un clic.
     */
    private String urlFotoPublica(Guia guia) {
        String url = guia.getUrlFoto();
        if (url == null || url.isBlank() || urlPublica.isEmpty()) {
            return url;
        }
        return urlPublica + url;
    }
}
