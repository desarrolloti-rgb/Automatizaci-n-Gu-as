package com.calimport.guias.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;

import com.calimport.guias.config.RutasConfig;
import com.calimport.guias.controller.dto.GenerarRutaRequest;
import com.calimport.guias.controller.dto.GenerarRutaRequest.GuiaEnRuta;
import com.calimport.guias.controller.dto.RutaResponse;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.OrigenHorario;
import com.calimport.guias.model.Repartidor;
import com.calimport.guias.model.Ruta;
import com.calimport.guias.repository.RutaRepository;
import com.calimport.guias.service.Geocodificador.Ubicacion;
import com.calimport.guias.service.InterpreteComentarios.Interpretacion;
import com.calimport.guias.service.OptimizadorRutas.Visita;
import com.calimport.guias.utils.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RutaServiceTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 9, 15);
    private static final LocalTime SALIDA = LocalTime.of(9, 30);
    private static final int REPARTIDOR = 7;

    @Mock
    private GuiaService guiaService;
    @Mock
    private RepartidorService repartidorService;
    @Mock
    private RutaRepository rutaRepository;
    @Mock
    private Geocodificador geocodificador;
    @Mock
    private InterpreteComentarios interpreteComentarios;
    @Mock
    private OptimizadorRutas optimizadorRutas;

    private RutasConfig config;
    private RutaService service;
    /** Las guías "en la BD": los mocks de GuiaService leen y escriben acá. */
    private final Map<Long, Guia> guias = new HashMap<>();

    @BeforeEach
    void setUp() {
        config = new RutasConfig();
        config.setOrigenLatitud(-33.40);
        config.setOrigenLongitud(-70.60);
        service = new RutaService(guiaService, repartidorService, rutaRepository, geocodificador,
                interpreteComentarios, optimizadorRutas, config, TransactionOperations.withoutTransaction());

        Repartidor repartidor = new Repartidor();
        repartidor.setEmployeeId(REPARTIDOR);
        repartidor.setNombre("Juan Perez");
        repartidor.setActivo(true);
        when(repartidorService.obtenerPorId(REPARTIDOR)).thenReturn(repartidor);

        when(guiaService.obtenerPorId(anyLong())).thenAnswer(inv -> guias.get(inv.<Long>getArgument(0)));
        when(guiaService.guardarUbicacion(anyLong(), anyDouble(), anyDouble(), anyBoolean())).thenAnswer(inv -> {
            Guia g = guias.get(inv.<Long>getArgument(0));
            g.setLatitud(inv.getArgument(1));
            g.setLongitud(inv.getArgument(2));
            g.setUbicacionAproximada(inv.getArgument(3));
            return g;
        });
        when(guiaService.guardarHorarioInterpretado(anyLong(), any(), any(), any())).thenAnswer(inv -> {
            Guia g = guias.get(inv.<Long>getArgument(0));
            g.setVentanaDesde(inv.getArgument(1));
            g.setVentanaHasta(inv.getArgument(2));
            g.setNotaEntrega(inv.getArgument(3));
            g.setOrigenHorario(OrigenHorario.COMENTARIO);
            return g;
        });
        when(guiaService.asignarRepartidor(anyLong(), anyInt())).thenAnswer(inv -> {
            Guia g = guias.get(inv.<Long>getArgument(0));
            g.setRepartidorId(inv.getArgument(1));
            return g;
        });
        when(rutaRepository.save(any(Ruta.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Guia guiaUbicada(long id) {
        Guia guia = new Guia((int) id, 1000 + id, "Cliente " + id, "Direccion " + id);
        guia.setId(id);
        guia.setLatitud(-33.0 - id / 100.0);
        guia.setLongitud(-70.0);
        guias.put(id, guia);
        return guia;
    }

    private static GenerarRutaRequest request(Long... ids) {
        return new GenerarRutaRequest(REPARTIDOR, FECHA, SALIDA,
                java.util.Arrays.stream(ids).map(id -> new GuiaEnRuta(id, null, null, null)).toList());
    }

    private void optimizadorDevuelve(long... ordenIds) {
        List<Visita> visitas = new java.util.ArrayList<>();
        Instant llegada = Instant.parse("2026-09-15T13:00:00Z"); // 10:00 en Chile
        for (long id : ordenIds) {
            visitas.add(new Visita(id, llegada));
            llegada = llegada.plusSeconds(1800);
        }
        when(optimizadorRutas.optimizar(eq(FECHA), eq(SALIDA), any())).thenReturn(visitas);
    }

    @Test
    void generaLaRutaEnElOrdenDelOptimizadorYAsignaLasGuias() {
        guiaUbicada(1);
        guiaUbicada(2);
        guiaUbicada(3);
        optimizadorDevuelve(3, 1, 2);

        RutaResponse ruta = service.generar(request(1L, 2L, 3L));

        assertEquals(List.of(3L, 1L, 2L), ruta.paradas().stream().map(RutaResponse.Parada::guiaId).toList());
        assertEquals(List.of(1, 2, 3), ruta.paradas().stream().map(RutaResponse.Parada::orden).toList());
        assertEquals(LocalTime.of(10, 0), ruta.paradas().get(0).llegadaEstimada());
        assertEquals(1, ruta.tramosGoogleMaps().size());
        assertTrue(ruta.paradas().get(0).wazeUrl().startsWith("https://waze.com/ul?ll="));
        guias.values().forEach(g -> assertEquals(REPARTIDOR, g.getRepartidorId()));
        verify(rutaRepository).borrarRuta(REPARTIDOR, FECHA);
    }

    @Test
    void geocodificaSoloLasGuiasQueNoTienenCoordenadas() {
        guiaUbicada(1);
        Guia sinUbicar = guiaUbicada(2);
        sinUbicar.setLatitud(null);
        sinUbicar.setLongitud(null);
        when(geocodificador.geocodificar("Direccion 2")).thenReturn(Optional.of(new Ubicacion(-33.5, -70.7, true)));
        optimizadorDevuelve(1, 2);

        RutaResponse ruta = service.generar(request(1L, 2L));

        verify(geocodificador, never()).geocodificar("Direccion 1");
        assertTrue(ruta.paradas().get(1).ubicacionAproximada());
        assertTrue(ruta.advertencias().stream().anyMatch(a -> a.contains("aproximada")));
    }

    @Test
    void siNoSeEncuentraUnaDireccionNoSeAsignaNada() {
        Guia sinUbicar = guiaUbicada(1);
        sinUbicar.setLatitud(null);
        sinUbicar.setLongitud(null);
        when(geocodificador.geocodificar(any())).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.generar(request(1L)));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, e.getStatus());
        assertTrue(e.getMessage().contains("1001"));
        verify(optimizadorRutas, never()).optimizar(any(), any(), any());
        verify(guiaService, never()).asignarRepartidor(anyLong(), anyInt());
    }

    @Test
    void interpretaElComentarioCuandoNadieDefinioElHorario() {
        Guia guia = guiaUbicada(1);
        guia.setComentario("solo mañanas");
        when(interpreteComentarios.interpretar("solo mañanas"))
                .thenReturn(new Interpretacion(LocalTime.of(9, 0), LocalTime.of(13, 0), null));
        optimizadorDevuelve(1);

        RutaResponse ruta = service.generar(request(1L));

        assertEquals(LocalTime.of(9, 0), ruta.paradas().get(0).ventanaDesde());
        assertEquals(OrigenHorario.COMENTARIO, ruta.paradas().get(0).origenHorario());
    }

    @Test
    void noVuelveAInterpretarUnComentarioYaInterpretado() {
        Guia guia = guiaUbicada(1);
        guia.setComentario("solo mañanas");
        guia.setOrigenHorario(OrigenHorario.COMENTARIO);
        optimizadorDevuelve(1);

        service.generar(request(1L));

        verify(interpreteComentarios, never()).interpretar(any());
    }

    @Test
    void siFallaLaInterpretacionDelComentarioLaRutaSeGeneraIgual() {
        Guia guia = guiaUbicada(1);
        guia.setComentario("solo mañanas");
        when(interpreteComentarios.interpretar(any())).thenThrow(new IllegalStateException("Vertex caido"));
        optimizadorDevuelve(1);

        RutaResponse ruta = service.generar(request(1L));

        assertEquals(1, ruta.paradas().size());
        // El comentario llega igual al repartidor.
        assertEquals("solo mañanas", ruta.paradas().get(0).comentario());
    }

    @Test
    void elHorarioDelBodegueroSeGuardaAntesDeOptimizar() {
        guiaUbicada(1);
        when(guiaService.definirHorario(1L, LocalTime.of(15, 0), null, null)).thenAnswer(inv -> {
            Guia g = guias.get(1L);
            g.setVentanaDesde(LocalTime.of(15, 0));
            g.setOrigenHorario(OrigenHorario.BODEGA);
            return g;
        });
        optimizadorDevuelve(1);

        service.generar(new GenerarRutaRequest(REPARTIDOR, FECHA, SALIDA,
                List.of(new GuiaEnRuta(1L, LocalTime.of(15, 0), null, null))));

        verify(guiaService).definirHorario(1L, LocalTime.of(15, 0), null, null);
        verify(interpreteComentarios, never()).interpretar(any());
    }

    @Test
    void avisaCuandoLaLlegadaQuedaFueraDeHorario() {
        Guia guia = guiaUbicada(1);
        guia.setVentanaHasta(LocalTime.of(9, 45));
        guia.setOrigenHorario(OrigenHorario.BODEGA);
        optimizadorDevuelve(1); // llega 10:00

        RutaResponse ruta = service.generar(request(1L));

        assertTrue(ruta.paradas().get(0).fueraDeHorario());
        assertEquals(1, ruta.advertencias().size());
        assertTrue(ruta.advertencias().get(0).contains("10:00"));
    }

    @Test
    void siFallaElOptimizadorNoSeAsignaNada() {
        guiaUbicada(1);
        when(optimizadorRutas.optimizar(any(), any(), any())).thenThrow(new IllegalStateException("timeout"));

        ApiException e = assertThrows(ApiException.class, () -> service.generar(request(1L)));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
        verify(guiaService, never()).asignarRepartidor(anyLong(), anyInt());
        verify(rutaRepository, never()).save(any());
    }

    @Test
    void rechazaUnaGuiaYaResuelta() {
        guiaUbicada(1).setEstado(EstadoGuia.ENTREGADA);

        ApiException e = assertThrows(ApiException.class, () -> service.generar(request(1L)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    void rechazaGuiasRepetidas() {
        guiaUbicada(1);

        ApiException e = assertThrows(ApiException.class, () -> service.generar(request(1L, 1L)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void rechazaUnRepartidorInactivo() {
        guiaUbicada(1);
        Repartidor inactivo = new Repartidor();
        inactivo.setEmployeeId(REPARTIDOR);
        inactivo.setNombre("Juan Perez");
        inactivo.setActivo(false);
        when(repartidorService.obtenerPorId(REPARTIDOR)).thenReturn(inactivo);

        ApiException e = assertThrows(ApiException.class, () -> service.generar(request(1L)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    void sinLaUbicacionDeLaBodegaNoSePuedeGenerar() {
        config.setOrigenLatitud(null);

        ApiException e = assertThrows(ApiException.class, () -> service.generar(request(1L)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
    }

    @Test
    void quinceParadasSalenEnDosTramosDeGoogleMaps() {
        Long[] ids = new Long[15];
        long[] orden = new long[15];
        for (int i = 0; i < 15; i++) {
            guiaUbicada(i + 1);
            ids[i] = (long) (i + 1);
            orden[i] = i + 1;
        }
        optimizadorDevuelve(orden);

        RutaResponse ruta = service.generar(request(ids));

        assertEquals(15, ruta.paradas().size());
        assertEquals(2, ruta.tramosGoogleMaps().size());
        assertFalse(ruta.paradas().stream().anyMatch(RutaResponse.Parada::fueraDeHorario));
    }
}
