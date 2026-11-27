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
import com.calimport.guias.controller.dto.OdometroRequest;
import com.calimport.guias.controller.dto.RutaResponse;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.EstadoRuta;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.OrigenHorario;
import com.calimport.guias.model.Repartidor;
import com.calimport.guias.model.Ruta;
import com.calimport.guias.repository.RutaRepository;
import com.calimport.guias.service.Geocodificador.Ubicacion;
import com.calimport.guias.service.InterpreteComentarios.Interpretacion;
import com.calimport.guias.service.OptimizadorRutas.Plan;
import com.calimport.guias.service.OptimizadorRutas.Visita;
import com.calimport.guias.utils.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        return requestConVuelta(null, ids);
    }

    private static GenerarRutaRequest requestConVuelta(Boolean vuelveABodega, Long... ids) {
        return new GenerarRutaRequest(REPARTIDOR, FECHA, SALIDA,
                java.util.Arrays.stream(ids).map(id -> new GuiaEnRuta(id, null, null, null)).toList(), vuelveABodega);
    }

    /** Termina a las 13:10 en Chile con 12,34 km. */
    private static final Instant TERMINO = Instant.parse("2026-09-15T16:10:00Z");

    private void optimizadorDevuelve(long... ordenIds) {
        List<Visita> visitas = new java.util.ArrayList<>();
        Instant llegada = Instant.parse("2026-09-15T13:00:00Z"); // 10:00 en Chile
        for (long id : ordenIds) {
            visitas.add(new Visita(id, llegada));
            llegada = llegada.plusSeconds(1800);
        }
        when(optimizadorRutas.optimizar(eq(FECHA), eq(SALIDA), anyBoolean(), any()))
                .thenReturn(new Plan(visitas, 12.34, TERMINO));
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
        verify(rutaRepository).borrarParadasPrevias(List.of(1L, 2L, 3L), REPARTIDOR, FECHA);
    }

    @Test
    void laRutaGuardaElPlanDelOptimizadorYLoDevuelve() {
        guiaUbicada(1);
        optimizadorDevuelve(1);

        RutaResponse ruta = service.generar(request(1L));

        assertEquals(12.3, ruta.kmPlanificados());
        assertEquals(LocalTime.of(13, 10), ruta.terminoEstimado());
        // De las 09:30 a las 13:10.
        assertEquals(220, ruta.minutosPlanificados());
    }

    @Test
    void sinIndicarLaVueltaSeUsaLoHabitualDelRepartidor() {
        guiaUbicada(1);
        optimizadorDevuelve(1);
        repartidorService.obtenerPorId(REPARTIDOR).setVuelveABodega(true);

        RutaResponse ruta = service.generar(request(1L));

        assertTrue(ruta.vuelveABodega());
        verify(optimizadorRutas).optimizar(eq(FECHA), eq(SALIDA), eq(true), any());
        verify(repartidorService, never()).recordarVuelta(anyInt(), anyBoolean());
    }

    @Test
    void laVueltaQueMarcaBodegaQuedaComoLoHabitualDelRepartidor() {
        guiaUbicada(1);
        optimizadorDevuelve(1);
        repartidorService.obtenerPorId(REPARTIDOR).setVuelveABodega(true);

        RutaResponse ruta = service.generar(requestConVuelta(false, 1L));

        assertFalse(ruta.vuelveABodega());
        verify(optimizadorRutas).optimizar(eq(FECHA), eq(SALIDA), eq(false), any());
        verify(repartidorService).recordarVuelta(REPARTIDOR, false);
    }

    @Test
    void regenerarReplanificaLaMismaRutaEnVezDeBorrarla() {
        // La ruta es lo que se mide: si se borrara, se perdería lo ya medido de ella.
        guiaUbicada(1);
        guiaUbicada(2);
        Ruta existente = new Ruta(REPARTIDOR, FECHA, LocalTime.of(8, 0));
        existente.setId(55L);
        when(rutaRepository.findByRepartidorIdAndFecha(REPARTIDOR, FECHA)).thenReturn(Optional.of(existente));
        optimizadorDevuelve(2, 1);

        RutaResponse ruta = service.generar(request(1L, 2L));

        assertEquals(55L, ruta.id());
        assertEquals(SALIDA, ruta.horaSalida());
        assertEquals(List.of(2L, 1L), ruta.paradas().stream().map(RutaResponse.Parada::guiaId).toList());
        verify(rutaRepository).save(existente);
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
    void siNoSeEncuentraLaDireccionEntraConSuComunaComoAproximada() {
        Guia sinUbicar = guiaUbicada(1);
        sinUbicar.setLatitud(null);
        sinUbicar.setLongitud(null);
        when(geocodificador.geocodificar("Direccion 1")).thenReturn(Optional.empty());
        when(geocodificador.aproximar("Direccion 1")).thenReturn(Optional.of(new Ubicacion(-33.5, -70.7, true)));
        optimizadorDevuelve(1);

        RutaResponse ruta = service.generar(request(1L));

        assertTrue(ruta.paradas().get(0).ubicacionAproximada());
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
        verify(optimizadorRutas, never()).optimizar(any(), any(), anyBoolean(), any());
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
    void elHorarioSaleDelPieDelDocumentoYNoDelComentario() {
        // El pie es de este despacho y llega limpio: solo la frase del "HORARIO:". El
        // Comments queda para las guias cuyo pie no dice nada de horario.
        Guia guia = guiaUbicada(1);
        guia.setHorarioFooter("LUNES A VIERNES 08:30 A 17:00 HORAS.");
        guia.setComentario("Dejar en bodega");
        when(interpreteComentarios.interpretar("LUNES A VIERNES 08:30 A 17:00 HORAS."))
                .thenReturn(new Interpretacion(LocalTime.of(8, 30), LocalTime.of(17, 0), null));
        optimizadorDevuelve(1);

        RutaResponse ruta = service.generar(request(1L));

        assertEquals(LocalTime.of(8, 30), ruta.paradas().get(0).ventanaDesde());
        verify(interpreteComentarios, never()).interpretar("Dejar en bodega");
    }

    @Test
    void elPieCompletoLeLlegaAlRepartidorEnLaParada() {
        // Ahi vienen el contacto y el telefono: en la calle, con el porton cerrado, es lo
        // unico que le sirve.
        Guia guia = guiaUbicada(1);
        guia.setFooter("DESPACHAR A: Av. Salvador 1150 CONTACTO: Patricia Rojas CEL: +56 9 8370 8082");
        optimizadorDevuelve(1);

        RutaResponse ruta = service.generar(request(1L));

        assertEquals("DESPACHAR A: Av. Salvador 1150 CONTACTO: Patricia Rojas CEL: +56 9 8370 8082",
                ruta.paradas().get(0).footer());
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
                List.of(new GuiaEnRuta(1L, LocalTime.of(15, 0), null, null)), null));

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
        when(optimizadorRutas.optimizar(any(), any(), anyBoolean(), any())).thenThrow(new IllegalStateException("timeout"));

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
    void rechazaUnaGuiaQueVaPorSamexAntesDeLlamarAFuera() {
        guiaUbicada(1);
        guiaUbicada(2).setTransportista("SAMEX");

        ApiException e = assertThrows(ApiException.class, () -> service.generar(request(1L, 2L)));

        // La lleva Samex: ni se ubica ni se ordena, y nada queda asignado.
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("1002"), e.getMessage());
        verify(optimizadorRutas, never()).optimizar(any(), any(), anyBoolean(), any());
        verify(guiaService, never()).asignarRepartidor(anyLong(), anyInt());
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

    @Test
    void conVueltaElLinkDeGoogleMapsTerminaEnLaBodega() {
        // El plan cuenta el regreso: si el link terminara en el último cliente, Maps
        // mostraría menos km que el plan.
        guiaUbicada(1);
        guiaUbicada(2);
        optimizadorDevuelve(1, 2);

        RutaResponse ruta = service.generar(requestConVuelta(true, 1L, 2L));

        assertEquals(List.of("https://www.google.com/maps/dir/?api=1&origin=-33.400000,-70.600000"
                + "&destination=-33.400000,-70.600000&travelmode=driving"
                + "&waypoints=-33.010000,-70.000000%7C-33.020000,-70.000000"), ruta.tramosGoogleMaps());
        // La bodega va solo en el link: no es una parada.
        assertEquals(2, ruta.paradas().size());
    }

    @Test
    void sinVueltaElLinkDeGoogleMapsTerminaEnElUltimoCliente() {
        guiaUbicada(1);
        guiaUbicada(2);
        optimizadorDevuelve(1, 2);

        RutaResponse ruta = service.generar(requestConVuelta(false, 1L, 2L));

        assertEquals(List.of("https://www.google.com/maps/dir/?api=1&origin=-33.400000,-70.600000"
                + "&destination=-33.020000,-70.000000&travelmode=driving"
                + "&waypoints=-33.010000,-70.000000"), ruta.tramosGoogleMaps());
    }

    @Test
    void conVueltaYDiezParadasElRegresoVaEnUnTramoAparte() {
        Long[] ids = new Long[10];
        long[] orden = new long[10];
        for (int i = 0; i < 10; i++) {
            guiaUbicada(i + 1);
            ids[i] = (long) (i + 1);
            orden[i] = i + 1;
        }
        optimizadorDevuelve(orden);

        RutaResponse ruta = service.generar(requestConVuelta(true, ids));

        assertEquals(2, ruta.tramosGoogleMaps().size());
        assertEquals("https://www.google.com/maps/dir/?api=1&origin=-33.100000,-70.000000"
                + "&destination=-33.400000,-70.600000&travelmode=driving", ruta.tramosGoogleMaps().get(1));
    }

    // --- Kilometraje real: el odómetro que anota el repartidor ------------------------

    private Ruta rutaDeHoy() {
        LocalDate hoy = LocalDate.now(config.zona());
        Ruta ruta = new Ruta(REPARTIDOR, hoy, SALIDA);
        ruta.setId(60L);
        when(rutaRepository.findByRepartidorIdAndFecha(REPARTIDOR, hoy)).thenReturn(Optional.of(ruta));
        return ruta;
    }

    @Test
    void elRepartidorAnotaElOdometroAlSalirEnSuRutaDeHoy() {
        Ruta ruta = rutaDeHoy();

        RutaResponse respuesta = service.anotarOdometro(REPARTIDOR, new OdometroRequest(12340, null));

        assertEquals(12340, respuesta.odometroSalida());
        assertNotNull(respuesta.odometroSalidaHora());
        assertNull(respuesta.odometroTermino());
        assertNull(respuesta.odometroTerminoHora());
        verify(rutaRepository).save(ruta);
    }

    @Test
    void alAnotarElTerminoSeConservaLaLecturaDeSalida() {
        Ruta ruta = rutaDeHoy();
        Instant anotadaAlSalir = Instant.parse("2026-09-15T12:50:00Z");
        ruta.anotarOdometroSalida(12340, anotadaAlSalir);

        RutaResponse respuesta = service.anotarOdometro(REPARTIDOR, new OdometroRequest(null, 12403));

        assertEquals(12340, respuesta.odometroSalida());
        assertEquals(12403, respuesta.odometroTermino());
        // Cuándo se anotó la salida es su respaldo: anotar el término no lo toca.
        assertEquals(anotadaAlSalir, ruta.getOdometroSalidaEn());
        assertNotNull(ruta.getOdometroTerminoEn());
    }

    @Test
    void elOdometroAlTerminarNoPuedeSerMenorQueAlSalir() {
        Ruta ruta = rutaDeHoy();
        ruta.anotarOdometroSalida(12340, Instant.now());

        ApiException e = assertThrows(ApiException.class,
                () -> service.anotarOdometro(REPARTIDOR, new OdometroRequest(null, 12300)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertNull(ruta.getOdometroTermino());
        verify(rutaRepository, never()).save(any());
    }

    @Test
    void corregirLaSalidaPorEncimaDelTerminoTambienSeRechaza() {
        Ruta ruta = rutaDeHoy();
        ruta.anotarOdometroSalida(12340, Instant.now());
        ruta.anotarOdometroTermino(12403, Instant.now());

        ApiException e = assertThrows(ApiException.class,
                () -> service.anotarOdometro(REPARTIDOR, new OdometroRequest(12500, null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals(12340, ruta.getOdometroSalida());
    }

    @Test
    void masDeQuinientosKmEnElDiaEsUnNumeroMalEscrito() {
        // 12.403 con un dígito de más: es un error al escribir, ninguna ruta de reparto llega a eso.
        rutaDeHoy().anotarOdometroSalida(12340, Instant.now());

        ApiException e = assertThrows(ApiException.class,
                () -> service.anotarOdometro(REPARTIDOR, new OdometroRequest(null, 124030)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void sinNingunaLecturaNoHayNadaQueAnotar() {
        rutaDeHoy();

        ApiException e = assertThrows(ApiException.class,
                () -> service.anotarOdometro(REPARTIDOR, new OdometroRequest(null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void sinRutaParaHoyNoSeAnotaElOdometro() {
        ApiException e = assertThrows(ApiException.class,
                () -> service.anotarOdometro(REPARTIDOR, new OdometroRequest(12340, null)));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void regenerarLaRutaConservaElKilometrajeAnotado() {
        // Bodega puede regenerar a media mañana, con el repartidor ya en la calle.
        guiaUbicada(1);
        Ruta existente = new Ruta(REPARTIDOR, FECHA, LocalTime.of(8, 0));
        existente.anotarOdometroSalida(12340, Instant.now());
        when(rutaRepository.findByRepartidorIdAndFecha(REPARTIDOR, FECHA)).thenReturn(Optional.of(existente));
        optimizadorDevuelve(1);

        RutaResponse ruta = service.generar(request(1L));

        assertEquals(12340, ruta.odometroSalida());
    }

    // --- historial ---

    private static RutaRepository.ConteoParadas conteo(long rutaId, EstadoGuia resultado, long cantidad) {
        return new RutaRepository.ConteoParadas() {
            public Long getRutaId() { return rutaId; }
            public EstadoGuia getResultado() { return resultado; }
            public long getCantidad() { return cantidad; }
        };
    }

    private Ruta rutaDelHistorial(long id, int repartidorId, LocalDate fecha, boolean vuelve) {
        Ruta ruta = new Ruta(repartidorId, fecha, SALIDA);
        ruta.setId(id);
        ruta.planificar(SALIDA, vuelve, 23.46, null);
        return ruta;
    }

    @Test
    void elHistorialCuentaLasParadasPorResultadoYLosKmDelOdometro() {
        Ruta ruta = rutaDelHistorial(1L, REPARTIDOR, FECHA, true);
        ruta.anotarOdometroSalida(12340, Instant.parse("2026-09-15T12:25:00Z"));
        ruta.anotarOdometroTermino(12371, Instant.parse("2026-09-15T20:10:00Z"));
        when(rutaRepository.findByFechaBetweenOrderByFechaDescRepartidorIdAsc(FECHA.minusDays(7), FECHA))
                .thenReturn(List.of(ruta));
        when(rutaRepository.contarParadas(List.of(1L))).thenReturn(List.of(
                conteo(1L, EstadoGuia.ENTREGADA, 3), conteo(1L, EstadoGuia.RECHAZADA, 1), conteo(1L, null, 1)));
        Repartidor juan = new Repartidor();
        juan.setEmployeeId(REPARTIDOR);
        juan.setNombre("Juan Perez");
        when(repartidorService.listar()).thenReturn(List.of(juan));

        var fila = service.historial(FECHA.minusDays(7), FECHA, null).get(0);

        assertEquals("Juan Perez", fila.repartidor());
        assertEquals(5, fila.paradas());
        assertEquals(3, fila.entregadas());
        assertEquals(1, fila.rechazadas());
        assertEquals(1, fila.pendientes());
        assertEquals(23.5, fila.kmPlanificados());
        assertEquals(31, fila.kmRecorridos());
        // FECHA ya pasó y quedó una parada sin resolver.
        assertEquals(EstadoRuta.CON_PENDIENTES, fila.estado());
    }

    @Test
    void elHistorialNombraAUnRepartidorQueYaNoEstaYSinOdometroNoTieneKmRecorridos() {
        Ruta ruta = rutaDelHistorial(2L, 8, FECHA, false);
        when(rutaRepository.findByRepartidorIdAndFechaBetweenOrderByFechaDesc(8, FECHA, FECHA))
                .thenReturn(List.of(ruta));
        when(rutaRepository.contarParadas(List.of(2L))).thenReturn(List.of(conteo(2L, EstadoGuia.ENTREGADA, 2)));
        when(repartidorService.listar()).thenReturn(List.of());

        var fila = service.historial(FECHA, FECHA, 8).get(0);

        assertEquals("Repartidor 8", fila.repartidor());
        assertNull(fila.kmRecorridos());
        assertEquals(EstadoRuta.COMPLETADA, fila.estado());
    }

    @Test
    void unHistorialVacioNoConsultaLasParadas() {
        when(rutaRepository.findByFechaBetweenOrderByFechaDescRepartidorIdAsc(any(), any())).thenReturn(List.of());

        assertTrue(service.historial(FECHA, FECHA, null).isEmpty());
        verify(rutaRepository, never()).contarParadas(any());
    }

    @Test
    void elHistorialSinFechasEsBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> service.historial(null, FECHA, null)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> service.historial(FECHA, null, null)).getStatus());
    }

    @Test
    void elHistorialRechazaUnRangoAlRevesOMasLargoQueTresMeses() {
        ApiException alReves = assertThrows(ApiException.class, () -> service.historial(FECHA, FECHA.minusDays(1), null));
        assertEquals(HttpStatus.BAD_REQUEST, alReves.getStatus());

        ApiException largo = assertThrows(ApiException.class,
                () -> service.historial(FECHA.minusDays(RutaService.MAX_DIAS_HISTORIAL), FECHA, null));
        assertEquals(HttpStatus.BAD_REQUEST, largo.getStatus());

        // Justo el máximo sí se puede.
        when(rutaRepository.findByFechaBetweenOrderByFechaDescRepartidorIdAsc(any(), any())).thenReturn(List.of());
        assertTrue(service.historial(FECHA.minusDays(RutaService.MAX_DIAS_HISTORIAL - 1), FECHA, null).isEmpty());
    }

    @Test
    void laRutaDiceElResultadoDeCadaParadaYSuEstado() {
        Guia guia = guiaUbicada(1L);
        Ruta ruta = new Ruta(REPARTIDOR, FECHA, SALIDA);
        ruta.agregarParada(guia, 1, FECHA.atTime(10, 0).atZone(config.zona()).toInstant());
        ruta.getParadas().get(0).resolver(EstadoGuia.ENTREGADA, Instant.parse("2026-09-15T13:05:00Z"));

        RutaResponse respuesta = service.aRespuesta(ruta);

        assertEquals(EstadoGuia.ENTREGADA, respuesta.paradas().get(0).resultado());
        assertEquals(EstadoRuta.COMPLETADA, respuesta.estado());
    }
}
