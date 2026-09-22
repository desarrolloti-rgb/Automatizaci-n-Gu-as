package com.calimport.guias.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.calimport.guias.sap.GuiaSap;
import com.calimport.guias.sap.SapClient;

import tools.jackson.databind.JsonNode;

/**
 * Trae las guías de despacho de SAP y las vuelca a la copia local.
 *
 * <p>Es idempotente: correrla dos veces no duplica nada, porque se apoya en
 * {@link GuiaService#sincronizarDesdeSap} — la primera corrida da de alta y las
 * siguientes solo refrescan lo que siga PENDIENTE.
 */
@Service
public class GuiaSyncService {

    private static final Logger log = LoggerFactory.getLogger(GuiaSyncService.class);

    private final SapClient sapClient;
    private final GuiaService guiaService;
    private final String filtroExtra;
    private final boolean udfEstadoLogistico;

    public GuiaSyncService(SapClient sapClient, GuiaService guiaService,
                            @Value("${guias.sync.filtro-extra:}") String filtroExtra,
                            @Value("${guias.sap.udf-estado-logistico:false}") boolean udfEstadoLogistico) {
        this.sapClient = sapClient;
        this.guiaService = guiaService;
        this.filtroExtra = filtroExtra;
        this.udfEstadoLogistico = udfEstadoLogistico;
    }

    /** Cuántas guías se sincronizaron y cuántas filas de SAP se descartaron por venir incompletas. */
    public record Resultado(int sincronizadas, int descartadas) {
    }

    public Resultado sincronizarDesde(LocalDate desde) {
        JsonNode respuesta = sapClient.fetchGuiasDeDespacho(desde, filtroExtra, udfEstadoLogistico);

        JsonNode value = respuesta == null ? null : respuesta.get("value");
        if (value == null || !value.isArray()) {
            log.warn("SAP no devolvio un arreglo 'value' al pedir guias desde {}", desde);
            return new Resultado(0, 0);
        }

        List<GuiaSap> guias = new ArrayList<>();
        int descartadas = 0;
        for (JsonNode fila : value) {
            GuiaSap guia = mapear(fila);
            if (guia == null) {
                descartadas++;
                continue;
            }
            guias.add(guia);
        }

        for (GuiaSap guia : guias) {
            guiaService.sincronizarDesdeSap(guia.docEntry(), guia.folio(), guia.cliente(), guia.direccion(),
                    guia.comentario());
        }

        log.info("Sincronizacion desde {}: {} guias, {} filas descartadas", desde, guias.size(), descartadas);
        return new Resultado(guias.size(), descartadas);
    }

    /**
     * Devuelve null si la fila no sirve. Sin DocEntry no hay forma de identificar la guía,
     * y sin folio el repartidor no podría cuadrarla con el papel que lleva: en ambos casos
     * es preferible saltarla y dejar registro, antes que guardar una guía a medias.
     */
    private GuiaSap mapear(JsonNode fila) {
        if (!fila.hasNonNull("DocEntry") || !fila.hasNonNull("FolioNumber")) {
            log.warn("Fila de SAP sin DocEntry o FolioNumber, se ignora: {}", fila);
            return null;
        }
        return new GuiaSap(
                fila.get("DocEntry").asInt(),
                fila.get("FolioNumber").asLong(),
                textoDe(fila, "CardName"),
                normalizarDireccion(direccionDeDespacho(fila)),
                unirLineas(textoDe(fila, "Comments"), " "));
    }

    /**
     * En SAP, {@code Address} es la direccion de <b>facturacion</b> y {@code Address2} la
     * de <b>despacho</b> — lo confirma el AddressExtension del documento, donde
     * ShipToStreet coincide con Address2 y BillToStreet con Address. Al repartidor le
     * sirve la de despacho: usar Address lo mandaria a donde se emite la factura.
     *
     * <p>Cuando el cliente no tiene una direccion de despacho aparte, Address2 puede venir
     * vacia; en ese caso se cae a Address, que para ese cliente son la misma.
     */
    private static String direccionDeDespacho(JsonNode fila) {
        String despacho = textoDe(fila, "Address2");
        return despacho.isEmpty() ? textoDe(fila, "Address") : despacho;
    }

    private static String textoDe(JsonNode fila, String campo) {
        return fila.hasNonNull(campo) ? fila.get(campo).asText("").trim() : "";
    }

    /**
     * SAP trae las direcciones con retornos de carro sueltos ({@code \r}, sin {@code \n}) y
     * lineas vacias: "SAN NICOLAS 630\r\r SANTIAGO\rCHILE". Tal cual, en el celular del
     * repartidor se ve todo pegoteado en una linea. Se parte por los saltos y se rearma
     * separando por comas.
     */
    private static String normalizarDireccion(String direccion) {
        return unirLineas(direccion, ", ");
    }

    private static String unirLineas(String texto, String separador) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(texto.split("[\\r\\n]+"))
                .map(String::trim)
                .filter(parte -> !parte.isEmpty())
                .reduce((a, b) -> a + separador + b)
                .orElse("");
    }
}
