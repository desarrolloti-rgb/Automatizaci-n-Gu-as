package com.calimport.guias.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.Repartidor;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.repository.RepartidorRepository;
import com.calimport.guias.sap.EstadoLogistico;
import com.calimport.guias.sap.SapClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SincronizacionSapServiceTest {

    @Mock
    private SapClient sapClient;
    @Mock
    private GuiaRepository guiaRepository;
    @Mock
    private RepartidorRepository repartidorRepository;

    private SincronizacionSapService service;

    @BeforeEach
    void setUp() {
        service = new SincronizacionSapService(sapClient, guiaRepository, repartidorRepository,
                "https://apps.calimport.cl/gd", true, false);
    }

    private Guia guiaEntregada() {
        Guia guia = new Guia(1001, 5555L, "Cliente X", "Av. Siempre Viva 742");
        guia.setId(1L);
        guia.setRepartidorId(7);
        guia.setRecibidaPorRepartidor(true);
        guia.setEstado(EstadoGuia.ENTREGADA);
        guia.setUrlFoto("/api/fotos/guia-1-abc.jpg");
        guia.setSincronizada(false);
        return guia;
    }

    private void hayRepartidor() {
        Repartidor r = new Repartidor();
        r.setEmployeeId(7);
        r.setNombre("Juan Pérez");
        when(repartidorRepository.findById(7)).thenReturn(Optional.of(r));
    }

    @Test
    void cuandoSapContestaLaGuiaQuedaSincronizada() {
        Guia guia = guiaEntregada();
        hayRepartidor();

        service.empujar(guia);

        assertTrue(guia.isSincronizada());
        verify(guiaRepository).save(guia);
    }

    @Test
    void mandaElDocEntryYLosCamposDelEstado() {
        Guia guia = guiaEntregada();
        hayRepartidor();

        service.empujar(guia);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cuerpo = ArgumentCaptor.forClass(Map.class);
        verify(sapClient).actualizarEstadoLogistico(eq(1001), cuerpo.capture());
        assertEquals("E", cuerpo.getValue().get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("Juan Pérez", cuerpo.getValue().get(EstadoLogistico.CAMPO_DESPACHADOR));
    }

    @Test
    void unaGuiaDeSamexVaConSamexComoDespachadorSinBuscarRepartidor() {
        Guia guia = new Guia(1001, 5555L, "Cliente X", "Av. Siempre Viva 742");
        guia.setId(1L);
        guia.setTransportista("SAMEX");
        guia.setRecibidaPorRepartidor(true);
        guia.setSincronizada(false);

        service.empujar(guia);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cuerpo = ArgumentCaptor.forClass(Map.class);
        verify(sapClient).actualizarEstadoLogistico(eq(1001), cuerpo.capture());
        assertEquals("T", cuerpo.getValue().get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("SAMEX", cuerpo.getValue().get(EstadoLogistico.CAMPO_DESPACHADOR));
        verify(repartidorRepository, never()).findById(any());
        assertTrue(guia.isSincronizada());
    }

    @Test
    void unaEntregaDeSamexVaConElLinkAlPdfYCabeEnElUdf() {
        Guia guia = new Guia(1001, 5555L, "Cliente X", "Av. Siempre Viva 742");
        // Un id de 6 dígitos: el caso más largo que se espera, como en el de las fotos.
        guia.setId(123456L);
        guia.setTransportista("SAMEX");
        guia.setRecibidaPorRepartidor(true);
        guia.setEstado(EstadoGuia.ENTREGADA);
        guia.setUrlFoto("/api/fotos/guia-123456-0f8fad5b-d9cb-469f-a165-70867728950e.pdf");
        guia.setSincronizada(false);

        service.empujar(guia);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cuerpo = ArgumentCaptor.forClass(Map.class);
        verify(sapClient).actualizarEstadoLogistico(eq(1001), cuerpo.capture());
        assertEquals("E", cuerpo.getValue().get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("SAMEX", cuerpo.getValue().get(EstadoLogistico.CAMPO_DESPACHADOR));
        String url = cuerpo.getValue().get(EstadoLogistico.CAMPO_URL_FOTO).toString();
        assertEquals("https://apps.calimport.cl/gd/api/evidencia/guia-123456-0f8fad5b-d9cb-469f-a165-70867728950e.pdf", url);
        assertTrue(url.length() <= 100, "U_UrlFoto mide 100: " + url.length());
    }

    @Test
    void laUrlDeLaFotoViajaAbsolutaParaQueSeAbraDesdeSap() {
        Guia guia = guiaEntregada();
        hayRepartidor();

        service.empujar(guia);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cuerpo = ArgumentCaptor.forClass(Map.class);
        verify(sapClient).actualizarEstadoLogistico(anyInt(), cuerpo.capture());
        // Quien la mira desde SAP está fuera de la app: una ruta relativa no le sirve, y la
        // de /api/fotos le pediría un token que no tiene.
        assertEquals("https://apps.calimport.cl/gd/api/evidencia/guia-1-abc.jpg",
                cuerpo.getValue().get(EstadoLogistico.CAMPO_URL_FOTO));
    }

    @Test
    void laUrlMasLargaPosibleEntraEnElUdf() {
        // U_UrlFoto mide 100 en productivo. Id de 6 dígitos, UUID y la extensión más larga.
        Guia guia = guiaEntregada();
        guia.setUrlFoto("/api/fotos/guia-999999-00000000-0000-0000-0000-000000000000.webp");
        hayRepartidor();

        service.empujar(guia);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cuerpo = ArgumentCaptor.forClass(Map.class);
        verify(sapClient).actualizarEstadoLogistico(anyInt(), cuerpo.capture());
        assertTrue(cuerpo.getValue().get(EstadoLogistico.CAMPO_URL_FOTO).toString().length() <= 100);
    }

    @Test
    void siSapNoContestaLaGuiaQuedaParaReintentoYNoSePropagaElError() {
        Guia guia = guiaEntregada();
        hayRepartidor();
        doThrow(new RuntimeException("connection timeout"))
                .when(sapClient).actualizarEstadoLogistico(anyInt(), any());

        // Lo esencial: NO lanza. El repartidor ya entregó y su trabajo no puede
        // deshacerse porque el Service Layer esté caído.
        service.empujar(guia);

        assertFalse(guia.isSincronizada());
        verify(guiaRepository).save(guia);
    }

    @Test
    void elReintentoRecorreSoloLasQueNoLlegaronASap() {
        Guia guia = guiaEntregada();
        hayRepartidor();
        when(guiaRepository.findBySincronizadaFalse()).thenReturn(List.of(guia));

        service.reintentarPendientes();

        verify(sapClient).actualizarEstadoLogistico(eq(1001), any());
        assertTrue(guia.isSincronizada());
    }

    @Test
    void sinNadaPendienteElReintentoNoTocaSap() {
        when(guiaRepository.findBySincronizadaFalse()).thenReturn(List.of());

        service.reintentarPendientes();

        verify(sapClient, never()).actualizarEstadoLogistico(anyInt(), any());
    }

    // --- cuándo quedó despachada ---

    @Test
    void laGuiaQuedaDespachadaCuandoLaFotoLlegaASap() {
        // No cuando el repartidor cerró la guía en el celular: hasta que la evidencia no
        // está en SAP, para el resto de la empresa esa entrega no existe.
        Guia guia = guiaEntregada();
        hayRepartidor();

        service.empujar(guia);

        assertNotNull(guia.getFechaFotoEnSap());
    }

    @Test
    void unReintentoNoCambiaCuandoLlegoLaFoto() {
        Guia guia = guiaEntregada();
        hayRepartidor();
        service.empujar(guia);
        Instant primera = guia.getFechaFotoEnSap();

        guia.setSincronizada(false);
        service.empujar(guia);

        assertEquals(primera, guia.getFechaFotoEnSap());
    }

    @Test
    void siSapNoContestaNoSeAnotaQueLaFotoLlego() {
        Guia guia = guiaEntregada();
        hayRepartidor();
        doThrow(new RuntimeException("connection timeout"))
                .when(sapClient).actualizarEstadoLogistico(anyInt(), any());

        service.empujar(guia);

        assertNull(guia.getFechaFotoEnSap());
    }

    @Test
    void unRetiroSinFotoNoMarcaLaGuiaComoDespachada() {
        // El paso P → T tambien empuja a SAP, pero ahi todavia no hay nada que entregar.
        Guia guia = guiaEntregada();
        guia.setEstado(EstadoGuia.PENDIENTE);
        guia.setUrlFoto(null);
        hayRepartidor();

        service.empujar(guia);

        assertNull(guia.getFechaFotoEnSap());
    }

    // --- fecha y hora de la entrega (U_Dateguia, U_Horaguia) ---

    @Test
    void conLosUdfDeFechaLaEntregaViajaConFechaYHora() {
        SincronizacionSapService conFecha = new SincronizacionSapService(
                sapClient, guiaRepository, repartidorRepository, "https://apps.calimport.cl/gd", true, true);
        Guia guia = guiaEntregada();
        guia.setFechaEntrega(Instant.parse("2026-11-04T19:41:21Z"));
        hayRepartidor();

        conFecha.empujar(guia);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cuerpo = ArgumentCaptor.forClass(Map.class);
        verify(sapClient).actualizarEstadoLogistico(eq(1001), cuerpo.capture());
        // En el mismo PATCH que el estado y la foto, no en uno aparte.
        assertEquals("E", cuerpo.getValue().get(EstadoLogistico.CAMPO_ESTADO));
        assertEquals("2026-11-04", cuerpo.getValue().get(EstadoLogistico.CAMPO_FECHA_ENTREGA));
        assertEquals("16:41:00", cuerpo.getValue().get(EstadoLogistico.CAMPO_HORA_ENTREGA));
    }

    @Test
    void sinLosUdfDeFechaNoSeMandan() {
        // Donde no existen (CALIMPORT_PRODUCTIVO al 05-11-2026), nombrarlos haria que SAP
        // rechace el PATCH completo y tampoco llegarian el estado ni la foto.
        Guia guia = guiaEntregada();
        guia.setFechaEntrega(Instant.parse("2026-11-04T19:41:21Z"));
        hayRepartidor();

        service.empujar(guia);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cuerpo = ArgumentCaptor.forClass(Map.class);
        verify(sapClient).actualizarEstadoLogistico(anyInt(), cuerpo.capture());
        assertFalse(cuerpo.getValue().containsKey(EstadoLogistico.CAMPO_FECHA_ENTREGA));
        assertFalse(cuerpo.getValue().containsKey(EstadoLogistico.CAMPO_HORA_ENTREGA));
    }

    // --- mientras los UDF no existan en SAP ---

    @Test
    void sinLosUdfCreadosNoSeLlamaASapYLaAppSigueFuncionando() {
        SincronizacionSapService sinUdf = new SincronizacionSapService(
                sapClient, guiaRepository, repartidorRepository, "https://apps.calimport.cl/gd", false, true);
        Guia guia = guiaEntregada();

        sinUdf.empujar(guia);
        sinUdf.reintentarPendientes();

        // Pedirle a SAP un campo que no existe no devuelve vacío: responde 400 y tumba la
        // operación. Verificado contra el Service Layer real el 22-09-2026.
        verify(sapClient, never()).actualizarEstadoLogistico(anyInt(), any());
        verify(guiaRepository, never()).findBySincronizadaFalse();
        // La entrega ya quedó guardada en Postgres por GuiaService: no se toca.
        assertFalse(guia.isSincronizada());
    }
}
