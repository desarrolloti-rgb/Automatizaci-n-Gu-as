package com.calimport.guias.service;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.calimport.guias.sap.SapClient;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuiaSyncServiceTest {

    private static final LocalDate DESDE = LocalDate.of(2026, 8, 1);

    @Mock
    private SapClient sapClient;
    @Mock
    private GuiaService guiaService;

    private GuiaSyncService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        service = new GuiaSyncService(sapClient, guiaService, "");
        mapper = new ObjectMapper();
    }

    /** Fila tal como la devuelve SAP con el $select acotado. */
    private ObjectNode fila(int docEntry, long folioNumber, String cardName, String direccionDespacho) {
        ObjectNode fila = mapper.createObjectNode();
        fila.put("DocEntry", docEntry);
        fila.put("FolioNumber", folioNumber);
        fila.put("CardName", cardName);
        fila.put("Address2", direccionDespacho);
        return fila;
    }

    private ObjectNode respuestaCon(ObjectNode... filas) {
        ArrayNode arr = mapper.createArrayNode();
        for (ObjectNode fila : filas) {
            arr.add(fila);
        }
        ObjectNode respuesta = mapper.createObjectNode();
        respuesta.set("value", arr);
        return respuesta;
    }

    @Test
    void elFolioSaleDeFolioNumberYNoDeDocNum() {
        // DocNum es la numeracion interna de SAP; FolioNumber es el que va impreso en la
        // guia que lleva el repartidor. Confundirlos le muestra un numero que no cuadra.
        ObjectNode fila = fila(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630");
        fila.put("DocNum", 9900001);
        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630", "");
    }

    @Test
    void seUsaLaDireccionDeDespachoYNoLaDeFacturacion() {
        // Caso real (DocEntry 46261): Address es la de facturacion y Address2 la de
        // despacho. Usar Address mandaria al repartidor a donde se emite la factura.
        ObjectNode fila = mapper.createObjectNode();
        fila.put("DocEntry", 46261);
        fila.put("FolioNumber", 148L);
        fila.put("CardName", "ALVARO IDRO RAMIREZ");
        fila.put("Address", "AV. VICUNA MACKENA 2289\rSAN JOAQUIN, SANTIAGO\rCHILE");
        fila.put("Address2", "PRUEBA\rSAN JOAQUIN, SANTIAGO\rCHILE");

        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(
                46261, 148L, "ALVARO IDRO RAMIREZ", "PRUEBA, SAN JOAQUIN, SANTIAGO, CHILE", "");
    }

    @Test
    void siNoHayDireccionDeDespachoSeCaeALaDeFacturacion() {
        // Cliente sin direccion de despacho aparte: para el son la misma.
        ObjectNode fila = mapper.createObjectNode();
        fila.put("DocEntry", 1);
        fila.put("FolioNumber", 187350L);
        fila.put("CardName", "CORESA S.A.");
        fila.put("Address", "SAN NICOLAS 630, SANTIAGO");

        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630, SANTIAGO", "");
    }

    @Test
    void laDireccionSeNormalizaParaQueSeLeaEnElCelular() {
        // SAP trae retornos de carro sueltos y lineas vacias.
        when(sapClient.fetchGuiasDeDespacho(DESDE, ""))
                .thenReturn(respuestaCon(fila(1, 187350L, "CORESA S.A.",
                        "SAN NICOLAS #  630\r\r SANTIAGO\rCHILE")));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(
                1, 187350L, "CORESA S.A.", "SAN NICOLAS #  630, SANTIAGO, CHILE", "");
    }

    @Test
    void sincronizaTodasLasGuiasDeLaRespuesta() {
        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon(
                fila(1, 187350L, "Cliente A", "Direccion A"),
                fila(2, 187351L, "Cliente B", "Direccion B"),
                fila(3, 187352L, "Cliente C", "Direccion C")));

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(3, resultado.sincronizadas());
        assertEquals(0, resultado.descartadas());
        verify(guiaService).sincronizarDesdeSap(1, 187350L, "Cliente A", "Direccion A", "");
        verify(guiaService).sincronizarDesdeSap(3, 187352L, "Cliente C", "Direccion C", "");
    }

    @Test
    void unaFilaSinDocEntryNoSeGuardaAMedias() {
        ObjectNode sinDocEntry = mapper.createObjectNode();
        sinDocEntry.put("FolioNumber", 187350L);
        sinDocEntry.put("CardName", "Cliente sin id");

        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon(sinDocEntry));

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(0, resultado.sincronizadas());
        assertEquals(1, resultado.descartadas());
        verify(guiaService, never()).sincronizarDesdeSap(anyInt(), any(), any(), any(), any());
    }

    @Test
    void unaFilaSinFolioSeDescartaPorqueElRepartidorNoPodriaCuadrarla() {
        ObjectNode sinFolio = mapper.createObjectNode();
        sinFolio.put("DocEntry", 1);
        sinFolio.put("CardName", "Cliente sin folio");

        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon(sinFolio));

        assertEquals(1, service.sincronizarDesde(DESDE).descartadas());
        verify(guiaService, never()).sincronizarDesdeSap(anyInt(), any(), any(), any(), any());
    }

    @Test
    void unaFilaMalaNoImpideSincronizarLasBuenas() {
        ObjectNode mala = mapper.createObjectNode();
        mala.put("CardName", "Fila incompleta");

        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon(
                fila(1, 187350L, "Cliente A", "Direccion A"),
                mala,
                fila(3, 187352L, "Cliente C", "Direccion C")));

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(2, resultado.sincronizadas());
        assertEquals(1, resultado.descartadas());
    }

    @Test
    void unaRespuestaVaciaDeSapNoRompeNada() {
        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon());

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(0, resultado.sincronizadas());
        verify(guiaService, never()).sincronizarDesdeSap(anyInt(), any(), any(), any(), any());
    }

    @Test
    void unaRespuestaSinArregloValueNoRompeNada() {
        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(mapper.createObjectNode());

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(0, resultado.sincronizadas());
        assertEquals(0, resultado.descartadas());
    }

    @Test
    void elFiltroExtraDeConfiguracionLlegaASap() {
        // Permite acotar a las guias que despacha Calimport sin recompilar, mientras se
        // confirma cual es el criterio correcto en esta instalacion.
        GuiaSyncService conFiltro = new GuiaSyncService(sapClient, guiaService, "U_TipoDesp eq '2'");
        when(sapClient.fetchGuiasDeDespacho(DESDE, "U_TipoDesp eq '2'")).thenReturn(respuestaCon());

        conFiltro.sincronizarDesde(DESDE);

        verify(sapClient).fetchGuiasDeDespacho(DESDE, "U_TipoDesp eq '2'");
    }

    @Test
    void unCampoDeTextoAusenteQuedaVacioEnLugarDeNull() {
        ObjectNode sinCliente = mapper.createObjectNode();
        sinCliente.put("DocEntry", 1);
        sinCliente.put("FolioNumber", 187350L);

        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon(sinCliente));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(eq(1), eq(187350L), eq(""), eq(""), eq(""));
    }

    @Test
    void elComentarioDeSapLlegaEnUnaSolaLinea() {
        // Ahi el vendedor anota el horario de recepcion; se interpreta recien al armar la ruta.
        ObjectNode fila = fila(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630");
        fila.put("Comments", "Recibe solo en la mañana\r\rllamar a Juan antes");
        when(sapClient.fetchGuiasDeDespacho(DESDE, "")).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630",
                "Recibe solo en la mañana llamar a Juan antes");
    }
}
