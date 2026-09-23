package com.calimport.guias.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.calimport.guias.model.OrigenDireccion;
import com.calimport.guias.sap.FooterDespacho;
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
        return sincronizar(desde, null);
    }

    /**
     * @param hasta última fecha incluida, o {@code null} para no acotar por arriba.
     */
    public Resultado sincronizar(LocalDate desde, LocalDate hasta) {
        JsonNode respuesta = sapClient.fetchGuiasDeDespacho(desde, hasta, filtroExtra, udfEstadoLogistico);

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
            guiaService.sincronizarDesdeSap(guia);
        }

        log.info("Sincronizacion {} a {}: {} guias, {} filas descartadas",
                desde, hasta == null ? "hoy" : hasta, guias.size(), descartadas);
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
        String pie = textoDe(fila, "ClosingRemarks");
        FooterDespacho footer = FooterDespacho.de(pie);
        boolean delPie = footer.direccionUbicable();

        return new GuiaSap(
                fila.get("DocEntry").asInt(),
                fila.get("FolioNumber").asLong(),
                recortar(textoDe(fila, "CardName"), 255),
                recortar(normalizarDireccion(delPie ? footer.direccion() : direccionDeLogistica(fila)), 255),
                delPie ? OrigenDireccion.FOOTER : OrigenDireccion.LOGISTICA,
                // Comments y el pie son dos campos distintos de SAP y se guardan por
                // separado: mezclarlos deja un texto que no es ninguno de los dos y que
                // despues no hay forma de volver a separar.
                recortar(unirLineas(textoDe(fila, "Comments"), " "), 2000),
                recortar(unirLineas(pie, " "), 2000),
                recortar(normalizarDireccion(footer.direccion()), 255),
                recortar(footer.horario(), 255));
    }

    /**
     * La direccion de la pestana Logistica del documento, que sale de la ficha del cliente.
     * Solo se usa cuando el pie no trae una que se pueda ubicar: es la que puede estar
     * desactualizada, y por eso la guia queda marcada con {@link OrigenDireccion#LOGISTICA}
     * para que bodega la confirme contra el pie.
     *
     * <p>En SAP {@code Address} es la direccion de <b>facturacion</b> y {@code Address2} la
     * de <b>despacho</b> — lo confirma el AddressExtension del documento, donde
     * ShipToStreet coincide con Address2 y BillToStreet con Address. Usar Address mandaria
     * al repartidor a donde se emite la factura; solo se recurre a ella cuando el cliente
     * no tiene direccion de despacho aparte y Address2 viene vacia.
     */
    private static String direccionDeLogistica(JsonNode fila) {
        String despacho = textoDe(fila, "Address2");
        return despacho.isEmpty() ? textoDe(fila, "Address") : despacho;
    }

    /**
     * Corta lo que no cabe en su columna. Un texto de SAP mas largo que la columna no
     * guarda una guia recortada: lanza, y esa excepcion se lleva por delante la
     * sincronizacion completa. Un comentario cortado es mucho mejor que ninguna guia.
     */
    private static String recortar(String texto, int largo) {
        return texto.length() <= largo ? texto : texto.substring(0, largo);
    }

    private static String textoDe(JsonNode fila, String campo) {
        return fila.hasNonNull(campo) ? fila.get(campo).asText("").trim() : "";
    }

    /**
     * SAP trae las direcciones con retornos de carro sueltos ({@code \r}, sin {@code \n}) y
     * lineas vacias: "SAN NICOLAS 630\r\r SANTIAGO\rCHILE". Tal cual, en el celular del
     * repartidor se ve todo pegoteado en una linea. Se parte por los saltos y se rearma
     * separando por comas.
     *
     * <p>El punto final se saca: las del pie del documento vienen escritas como frase
     * ("..., PEÑAFLOR.") y ese punto viaja tal cual a la consulta del geocodificador.
     */
    private static String normalizarDireccion(String direccion) {
        return unirLineas(direccion, ", ").replaceAll("\\.+$", "").trim();
    }

    private static String unirLineas(String texto, String separador) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        // Tambien por tabulacion: el pie del documento separa sus secciones con "\t\r".
        return java.util.Arrays.stream(texto.split("[\\t\\r\\n]+"))
                .map(String::trim)
                .filter(parte -> !parte.isEmpty())
                .reduce((a, b) -> a + separador + b)
                .orElse("");
    }
}
