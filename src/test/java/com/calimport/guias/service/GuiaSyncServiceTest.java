package com.calimport.guias.service;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.calimport.guias.model.OrigenDireccion;
import com.calimport.guias.sap.GuiaSap;
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
        service = new GuiaSyncService(sapClient, guiaService, "", false);
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

    /**
     * Lo que la sincronización le pasa al servicio cuando el pie del documento no aporta
     * nada: la dirección sale de la ficha del cliente (LOGISTICA) y los tres campos del pie
     * quedan vacíos. Los tests que miran el pie arman el GuiaSap a mano, porque es
     * justamente eso lo que prueban.
     */
    private static GuiaSap sap(int docEntry, long folio, String cliente, String direccion, String comentario) {
        return new GuiaSap(docEntry, folio, cliente, direccion, OrigenDireccion.LOGISTICA, comentario, "", "", "");
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
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(sap(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630", ""));
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

        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(
                sap(46261, 148L, "ALVARO IDRO RAMIREZ", "PRUEBA, SAN JOAQUIN, SANTIAGO, CHILE", ""));
    }

    @Test
    void siNoHayDireccionDeDespachoSeCaeALaDeFacturacion() {
        // Cliente sin direccion de despacho aparte: para el son la misma.
        ObjectNode fila = mapper.createObjectNode();
        fila.put("DocEntry", 1);
        fila.put("FolioNumber", 187350L);
        fila.put("CardName", "CORESA S.A.");
        fila.put("Address", "SAN NICOLAS 630, SANTIAGO");

        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(sap(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630, SANTIAGO", ""));
    }

    @Test
    void laDireccionSeNormalizaParaQueSeLeaEnElCelular() {
        // SAP trae retornos de carro sueltos y lineas vacias.
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false))
                .thenReturn(respuestaCon(fila(1, 187350L, "CORESA S.A.",
                        "SAN NICOLAS #  630\r\r SANTIAGO\rCHILE")));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(
                sap(1, 187350L, "CORESA S.A.", "SAN NICOLAS #  630, SANTIAGO, CHILE", ""));
    }

    @Test
    void sincronizaTodasLasGuiasDeLaRespuesta() {
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(
                fila(1, 187350L, "Cliente A", "Direccion A"),
                fila(2, 187351L, "Cliente B", "Direccion B"),
                fila(3, 187352L, "Cliente C", "Direccion C")));

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(3, resultado.sincronizadas());
        assertEquals(0, resultado.descartadas());
        verify(guiaService).sincronizarDesdeSap(sap(1, 187350L, "Cliente A", "Direccion A", ""));
        verify(guiaService).sincronizarDesdeSap(sap(3, 187352L, "Cliente C", "Direccion C", ""));
    }

    @Test
    void unaFilaSinDocEntryNoSeGuardaAMedias() {
        ObjectNode sinDocEntry = mapper.createObjectNode();
        sinDocEntry.put("FolioNumber", 187350L);
        sinDocEntry.put("CardName", "Cliente sin id");

        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(sinDocEntry));

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(0, resultado.sincronizadas());
        assertEquals(1, resultado.descartadas());
        verify(guiaService, never()).sincronizarDesdeSap(any());
    }

    @Test
    void unaFilaSinFolioSeDescartaPorqueElRepartidorNoPodriaCuadrarla() {
        ObjectNode sinFolio = mapper.createObjectNode();
        sinFolio.put("DocEntry", 1);
        sinFolio.put("CardName", "Cliente sin folio");

        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(sinFolio));

        assertEquals(1, service.sincronizarDesde(DESDE).descartadas());
        verify(guiaService, never()).sincronizarDesdeSap(any());
    }

    @Test
    void unaFilaMalaNoImpideSincronizarLasBuenas() {
        ObjectNode mala = mapper.createObjectNode();
        mala.put("CardName", "Fila incompleta");

        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(
                fila(1, 187350L, "Cliente A", "Direccion A"),
                mala,
                fila(3, 187352L, "Cliente C", "Direccion C")));

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(2, resultado.sincronizadas());
        assertEquals(1, resultado.descartadas());
    }

    @Test
    void unaRespuestaVaciaDeSapNoRompeNada() {
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon());

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(0, resultado.sincronizadas());
        verify(guiaService, never()).sincronizarDesdeSap(any());
    }

    @Test
    void unaRespuestaSinArregloValueNoRompeNada() {
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(mapper.createObjectNode());

        GuiaSyncService.Resultado resultado = service.sincronizarDesde(DESDE);

        assertEquals(0, resultado.sincronizadas());
        assertEquals(0, resultado.descartadas());
    }

    @Test
    void elRangoDeFechasLlegaASap() {
        LocalDate hasta = LocalDate.of(2026, 8, 31);
        when(sapClient.fetchGuiasDeDespacho(DESDE, hasta, "", false)).thenReturn(respuestaCon());

        service.sincronizar(DESDE, hasta);

        verify(sapClient).fetchGuiasDeDespacho(DESDE, hasta, "", false);
    }

    @Test
    void sinHastaNoSeAcotaPorArriba() {
        // null y no "hoy": la sincronizacion habitual trae todo de la fecha en adelante.
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon());

        service.sincronizarDesde(DESDE);

        verify(sapClient).fetchGuiasDeDespacho(DESDE, null, "", false);
    }

    @Test
    void elFiltroExtraDeConfiguracionLlegaASap() {
        // Permite acotar a las guias que despacha Calimport sin recompilar, mientras se
        // confirma cual es el criterio correcto en esta instalacion.
        GuiaSyncService conFiltro = new GuiaSyncService(sapClient, guiaService, "U_TipoDesp eq '2'", false);
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "U_TipoDesp eq '2'", false)).thenReturn(respuestaCon());

        conFiltro.sincronizarDesde(DESDE);

        verify(sapClient).fetchGuiasDeDespacho(DESDE, null, "U_TipoDesp eq '2'", false);
    }

    @Test
    void unCampoDeTextoAusenteQuedaVacioEnLugarDeNull() {
        ObjectNode sinCliente = mapper.createObjectNode();
        sinCliente.put("DocEntry", 1);
        sinCliente.put("FolioNumber", 187350L);

        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(sinCliente));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(sap(1, 187350L, "", "", ""));
    }

    /** Pie del DocEntry 46209 tal como lo devuelve el Service Layer. */
    private static final String PIE_REAL = "DESPACHAR A:\t\rAV.BERLIN PARCELA 34 C, COLONIA ALEMANA, PEÑAFLOR."
            + "\t\rCONTACTO:\t\rJUAN ELIAS ESCUDERO\t\rCEL: +56 9 8370 8082"
            + "\t\rHORARIO: LUNES A VIERNES 08:30 A 17:00 HORAS.";

    @Test
    void elPieYElComentarioDeSapSonCamposDistintosYNoSeMezclan() {
        // Comments y ClosingRemarks son dos campos de SAP. Juntarlos deja un texto que no es
        // ninguno de los dos y que despues no hay forma de volver a separar.
        ObjectNode fila = fila(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630");
        fila.put("ClosingRemarks", "HORARIO: 08:30 A 17:00");
        fila.put("Comments", "Dejar en bodega");
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(new GuiaSap(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630",
                OrigenDireccion.LOGISTICA, "Dejar en bodega",
                "HORARIO: 08:30 A 17:00", "", "08:30 A 17:00"));
    }

    @Test
    void laDireccionYElHorarioSalenDeLosComentariosDelDocumento() {
        // Guia 296475, tal cual. Lo unico que se espera de este pie: la direccion es
        // "Bodega Planta ... MOSTAZAL" y el horario "Lunes a Viernes de 8:30 a 13:00 horas".
        // El contacto queda fuera de los dos, pegado al final.
        String pie = "Despachar A Bodega Planta, SITE CPP (Promedio) KM. 63 LONGITUDINAL SUR - "
                + "SAN FRANCISCO MOSTAZAL Horario de recepción Lunes a Viernes de 8:30 a 13:00 horas "
                + "Pedro Zarate +569 6845 9569";
        ObjectNode fila = fila(1, 296475L, "Cliente", "Otra direccion de la ficha 123, Santiago");
        fila.put("ClosingRemarks", pie);
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(new GuiaSap(1, 296475L, "Cliente",
                "Bodega Planta, SITE CPP (Promedio) KM. 63 LONGITUDINAL SUR - SAN FRANCISCO MOSTAZAL",
                OrigenDireccion.FOOTER, "", pie,
                "Bodega Planta, SITE CPP (Promedio) KM. 63 LONGITUDINAL SUR - SAN FRANCISCO MOSTAZAL",
                "Lunes a Viernes de 8:30 a 13:00 horas"));
    }

    @Test
    void laDireccionDelPieLeGanaALaDeLaFichaDelCliente() {
        // Caso real (DocEntry 46209): la ficha dice "AV BERLIN PARCELA 34" y el pie
        // "PARCELA 34 C". El repartidor necesita la del pie, que es la que escribio quien
        // vendio y sabe a donde va el camion.
        ObjectNode fila = fila(46209, 296993L, "CHILEMPACK S.A.",
                "AV BERLIN PARCELA 34 COLONIA ALEMANA\rPEÑAFLOR, SANTIAGO\rCHILE");
        fila.put("ClosingRemarks", PIE_REAL);
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(new GuiaSap(46209, 296993L, "CHILEMPACK S.A.",
                "AV.BERLIN PARCELA 34 C, COLONIA ALEMANA, PEÑAFLOR", OrigenDireccion.FOOTER, "",
                // El pie crudo se guarda entero: es contra esto que bodega compara, y es de
                // donde el repartidor saca el contacto y el telefono.
                "DESPACHAR A: AV.BERLIN PARCELA 34 C, COLONIA ALEMANA, PEÑAFLOR. CONTACTO: JUAN ELIAS ESCUDERO"
                        + " CEL: +56 9 8370 8082 HORARIO: LUNES A VIERNES 08:30 A 17:00 HORAS.",
                "AV.BERLIN PARCELA 34 C, COLONIA ALEMANA, PEÑAFLOR",
                "LUNES A VIERNES 08:30 A 17:00 HORAS."));
    }

    @Test
    void elHorarioSeSacaSoloDeSuEtiquetaYNoDelPieEntero() {
        // Lo que se guarda como horario es la frase del "HORARIO:" y nada mas: asi no hay
        // forma de que el telefono del contacto se lea como una hora.
        ObjectNode fila = fila(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630");
        fila.put("ClosingRemarks", "CONTACTO:\t\rJUAN\t\rCEL: +56 9 8370 8082\t\rHORARIO: 08:30 A 17:00");
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(new GuiaSap(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630",
                OrigenDireccion.LOGISTICA, "",
                "CONTACTO: JUAN CEL: +56 9 8370 8082 HORARIO: 08:30 A 17:00", "", "08:30 A 17:00"));
    }

    @Test
    void unaGuiaQueVaPorTransportistaLoAvisaAlPrincipioDelComentario() {
        // Pie real: va por SAMEX a Calama. Si bodega no lo ve, la mete en una ruta de
        // Santiago y el optimizador arma un viaje de 1.600 km.
        ObjectNode fila = fila(1, 187350L, "Cliente", "SAN NICOLAS 630, SANTIAGO");
        fila.put("ClosingRemarks", "PAGADO (TRANSFERENCIA) DESPACHAR  VIA SAMEX A "
                + "Condominio Verdes Campiñas 3179, Pasaje 5 casa 34, Calama. - CONTACTO: ROBERTO FERNANDEZ "
                + "- TELEFONO: 569 5060 6296");
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(new GuiaSap(1, 187350L, "Cliente",
                "Condominio Verdes Campiñas 3179, Pasaje 5 casa 34, Calama", OrigenDireccion.FOOTER, "",
                "PAGADO (TRANSFERENCIA) DESPACHAR  VIA SAMEX A Condominio Verdes Campiñas 3179, "
                        + "Pasaje 5 casa 34, Calama. - CONTACTO: ROBERTO FERNANDEZ - TELEFONO: 569 5060 6296",
                "Condominio Verdes Campiñas 3179, Pasaje 5 casa 34, Calama", ""));
    }

    @Test
    void unNombreDeLugarNoPisaLaDireccionPeroSiLlegaAlRepartidor() {
        // "BODEGA Fruna" no se puede geocodificar: la direccion sigue siendo la de la ficha
        // y el texto del pie viaja en el comentario, que es donde sirve.
        ObjectNode fila = fila(1, 187350L, "Cliente", "SAN NICOLAS 630, SANTIAGO");
        fila.put("ClosingRemarks", "Despachar a BODEGA Fruna \nHorario colación 13 a 15Hrs"
                + "\nAt. Sr. Juan Mora Cel.: +56 9 8573 0973");
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(new GuiaSap(1, 187350L, "Cliente", "SAN NICOLAS 630, SANTIAGO",
                OrigenDireccion.LOGISTICA, "",
                "Despachar a BODEGA Fruna Horario colación 13 a 15Hrs At. Sr. Juan Mora Cel.: +56 9 8573 0973",
                // Se guarda igual aunque no se use como dirección: es lo que dice el pie.
                "BODEGA Fruna", "colación 13 a 15Hrs"));
    }

    @Test
    void unPieSinDireccionNoPisaLaDeLaFicha() {
        // El pie solo trae el horario: la direccion sigue saliendo de Address2, como antes.
        ObjectNode fila = fila(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630, SANTIAGO");
        fila.put("ClosingRemarks", "HORARIO: hasta las 12");
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(new GuiaSap(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630, SANTIAGO",
                OrigenDireccion.LOGISTICA, "",
                "HORARIO: hasta las 12", "", "hasta las 12"));
    }

    @Test
    void elComentarioDeSapLlegaEnUnaSolaLinea() {
        // Ahi el vendedor anota el horario de recepcion; se interpreta recien al armar la ruta.
        ObjectNode fila = fila(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630");
        fila.put("Comments", "Recibe solo en la mañana\r\rllamar a Juan antes");
        when(sapClient.fetchGuiasDeDespacho(DESDE, null, "", false)).thenReturn(respuestaCon(fila));

        service.sincronizarDesde(DESDE);

        verify(guiaService).sincronizarDesdeSap(sap(1, 187350L, "CORESA S.A.", "SAN NICOLAS 630",
                "Recibe solo en la mañana llamar a Juan antes"));
    }
}
