package com.calimport.guias.service;

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
                "https://apps.calimport.cl/gd", true);
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
    void laUrlDeLaFotoViajaAbsolutaParaQueSeAbraDesdeSap() {
        Guia guia = guiaEntregada();
        hayRepartidor();

        service.empujar(guia);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cuerpo = ArgumentCaptor.forClass(Map.class);
        verify(sapClient).actualizarEstadoLogistico(anyInt(), cuerpo.capture());
        // Quien la mira desde SAP está fuera de la app: una ruta relativa no le sirve.
        assertEquals("https://apps.calimport.cl/gd/api/fotos/guia-1-abc.jpg",
                cuerpo.getValue().get(EstadoLogistico.CAMPO_URL_FOTO));
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

    // --- mientras los UDF no existan en SAP ---

    @Test
    void sinLosUdfCreadosNoSeLlamaASapYLaAppSigueFuncionando() {
        SincronizacionSapService sinUdf = new SincronizacionSapService(
                sapClient, guiaRepository, repartidorRepository, "https://apps.calimport.cl/gd", false);
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
