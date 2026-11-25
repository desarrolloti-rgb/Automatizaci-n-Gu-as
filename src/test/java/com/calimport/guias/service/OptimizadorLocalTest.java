package com.calimport.guias.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.calimport.guias.config.RutasConfig;
import com.calimport.guias.model.Guia;
import com.calimport.guias.service.OptimizadorRutas.Visita;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El orden y las horas del optimizador gratuito, con puntos inventados sobre una recta. */
class OptimizadorLocalTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 9, 15);
    private static final LocalTime SALIDA = LocalTime.of(9, 0);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    /** Bodega en Padre Orellana 1236. */
    private static final double BODEGA_LAT = -33.458472;
    private static final double BODEGA_LNG = -70.631971;

    /** ~1,1 km por cada 0,01 grados de latitud. */
    private static final double KM_POR_CENTESIMA = 1.112;

    private RutasConfig config;
    private OptimizadorLocal optimizador;

    @BeforeEach
    void setUp() {
        config = new RutasConfig();
        config.setOrigenLatitud(BODEGA_LAT);
        config.setOrigenLongitud(BODEGA_LNG);
        config.setMinutosPorParada(10);
        config.setVelocidadPromedioKmh(25);
        optimizador = new OptimizadorLocal(config);
    }

    /** Guía al sur de la bodega, a {@code centesimas} de grado. */
    private static Guia guiaAlSur(long id, double centesimas) {
        Guia guia = new Guia((int) id, 1000 + id, "Cliente " + id, "Direccion " + id);
        guia.setId(id);
        guia.setLatitud(BODEGA_LAT - centesimas / 100);
        guia.setLongitud(BODEGA_LNG);
        return guia;
    }

    private static Guia guiaEn(long id, double latitud, double longitud) {
        Guia guia = guiaAlSur(id, 0);
        guia.setLatitud(latitud);
        guia.setLongitud(longitud);
        return guia;
    }

    /** Las visitas de una ruta que termina en el último cliente, como la mayoría de las pruebas. */
    private List<Visita> optimizar(List<Guia> guias) {
        return optimizador.optimizar(FECHA, SALIDA, false, guias).visitas();
    }

    private static List<Long> orden(List<Visita> visitas) {
        return visitas.stream().map(Visita::guiaId).toList();
    }

    private static LocalTime hora(Visita visita) {
        return visita.llegada().atZone(CHILE).toLocalTime();
    }

    @Test
    void sinHorariosVaDeLaMasCercanaALaMasLejana() {
        List<Guia> guias = List.of(guiaAlSur(1, 6), guiaAlSur(2, 2), guiaAlSur(3, 4));

        assertEquals(List.of(2L, 3L, 1L), orden(optimizar(guias)));
    }

    @Test
    void laLlegadaSumaViajeYTiempoDeDescarga() {
        List<Visita> visitas = optimizar(List.of(guiaAlSur(1, 1), guiaAlSur(2, 2)));

        // 1,112 km x 1,35 a 25 km/h = 3,6 min de viaje entre cada punto.
        double viaje = KM_POR_CENTESIMA * OptimizadorLocal.FACTOR_CALLES / 25 * 60;
        Instant salida = FECHA.atTime(SALIDA).atZone(CHILE).toInstant();
        assertEquals(viaje, minutosEntre(salida, visitas.get(0).llegada()), 0.1);
        assertEquals(viaje + 10 + viaje, minutosEntre(salida, visitas.get(1).llegada()), 0.1);
    }

    @Test
    void priorizaAlClienteQueCierraTemprano() {
        // La lejana cierra 09:30: pasando primero por la cercana no alcanza a llegar.
        Guia cercana = guiaAlSur(1, 2);
        Guia lejanaQueCierra = guiaAlSur(2, 8);
        lejanaQueCierra.setVentanaHasta(LocalTime.of(9, 30));

        List<Visita> visitas = optimizar(List.of(cercana, lejanaQueCierra));

        assertEquals(List.of(2L, 1L), orden(visitas));
        assertTrue(hora(visitas.get(0)).isBefore(LocalTime.of(9, 30)));
    }

    @Test
    void siLlegaAntesDeQueAbranEsperaYNoLaMarcaTemprano() {
        Guia abreTarde = guiaAlSur(1, 1);
        abreTarde.setVentanaDesde(LocalTime.of(11, 0));

        List<Visita> visitas = optimizar(List.of(abreTarde));

        assertEquals(LocalTime.of(11, 0), hora(visitas.get(0)));
    }

    @Test
    void dejaParaDespuesAlQueAbreMasTarde() {
        // Visitar primero a la que abre a las 12 obligaría a esperar y atrasaría a las demás.
        Guia abreAlMediodia = guiaAlSur(1, 1);
        abreAlMediodia.setVentanaDesde(LocalTime.of(12, 0));
        Guia cierraTemprano = guiaAlSur(2, 3);
        cierraTemprano.setVentanaHasta(LocalTime.of(11, 0));

        List<Visita> visitas = optimizar(List.of(abreAlMediodia, cierraTemprano, guiaAlSur(3, 2)));

        assertEquals(1L, visitas.get(visitas.size() - 1).guiaId());
        visitas.forEach(v -> assertTrue(v.guiaId() != 2L || hora(v).isBefore(LocalTime.of(11, 0))));
    }

    @Test
    void unHorarioImposibleNoSacaLaGuiaDeLaRuta() {
        // Cerró antes de la salida: igual va en la ruta (RutaService la marca fuera de horario).
        Guia yaCerro = guiaAlSur(1, 2);
        yaCerro.setVentanaHasta(LocalTime.of(8, 0));

        assertEquals(List.of(1L, 2L), orden(optimizar(List.of(yaCerro, guiaAlSur(2, 4)))));
    }

    @Test
    void mejoraUnOrdenMaloConPuntosDispersos() {
        // Ida y vuelta por la misma recta: el orden óptimo es monótono por distancia.
        List<Guia> guias = new ArrayList<>();
        double[] centesimas = {9, 1, 7, 3, 5, 2, 8, 4, 6};
        for (int i = 0; i < centesimas.length; i++) {
            guias.add(guiaAlSur(i + 1, centesimas[i]));
        }

        List<Long> ordenado = orden(optimizar(guias));

        assertEquals(List.of(2L, 6L, 4L, 8L, 5L, 9L, 3L, 7L, 1L), ordenado);
    }

    @Test
    void cincuentaGuiasSeResuelvenRapido() {
        List<Guia> guias = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Guia g = guiaAlSur(i + 1, 0);
            // Una grilla de 5x10 alrededor de la bodega, en orden revuelto.
            int fila = (i * 7) % 5;
            int columna = (i * 3) % 10;
            g.setLatitud(BODEGA_LAT - 0.02 * fila);
            g.setLongitud(BODEGA_LNG + 0.02 * columna - 0.1);
            if (i % 6 == 0) {
                g.setVentanaHasta(LocalTime.of(13, 0));
            }
            guias.add(g);
        }

        long inicio = System.nanoTime();
        List<Visita> visitas = optimizar(guias);
        long milis = Duration.ofNanos(System.nanoTime() - inicio).toMillis();

        assertEquals(50, visitas.size());
        assertEquals(50, visitas.stream().map(Visita::guiaId).distinct().count());
        assertTrue(milis < 5_000, "tardó " + milis + " ms");
    }

    @Test
    void conVueltaABodegaTerminaCercaDeLaBodegaYSinVueltaEnLoMasLejano() {
        // Dos clientes a ~1 km de la bodega (este y oeste) y uno a ~10 km al sur. Sin vuelta
        // conviene dejar el lejano para el final; con vuelta, eso obligaría a volver 10 km, así
        // que el recorrido baja por un lado y sube por el otro, terminando junto a la bodega.
        Guia lejana = guiaEn(1, BODEGA_LAT - 0.09, BODEGA_LNG);
        Guia este = guiaEn(2, BODEGA_LAT, BODEGA_LNG + 0.01);
        Guia oeste = guiaEn(3, BODEGA_LAT, BODEGA_LNG - 0.01);
        List<Guia> guias = List.of(lejana, este, oeste);

        List<Visita> sinVuelta = optimizador.optimizar(FECHA, SALIDA, false, guias).visitas();
        List<Visita> conVuelta = optimizador.optimizar(FECHA, SALIDA, true, guias).visitas();

        assertEquals(1L, sinVuelta.get(2).guiaId());
        assertEquals(1L, conVuelta.get(1).guiaId(), "la lejana va al medio, no al final");
    }

    @Test
    void elPlanTraeLosKmYElTerminoYLaVueltaLosSuma() {
        Guia guia = guiaAlSur(1, 1);
        double km = KM_POR_CENTESIMA * OptimizadorLocal.FACTOR_CALLES;
        double viaje = km / 25 * 60;
        Instant salida = FECHA.atTime(SALIDA).atZone(CHILE).toInstant();

        OptimizadorRutas.Plan sinVuelta = optimizador.optimizar(FECHA, SALIDA, false, List.of(guia));
        OptimizadorRutas.Plan conVuelta = optimizador.optimizar(FECHA, SALIDA, true, List.of(guia));

        // Sin vuelta termina al salir del cliente: viaje + 10 min de descarga.
        assertEquals(km, sinVuelta.kilometros(), 0.01);
        assertEquals(viaje + 10, minutosEntre(salida, sinVuelta.termino()), 0.1);
        // Con vuelta suma el regreso: el doble de km y otro viaje.
        assertEquals(2 * km, conVuelta.kilometros(), 0.01);
        assertEquals(viaje + 10 + viaje, minutosEntre(salida, conVuelta.termino()), 0.1);
        // La llegada al cliente es la misma: la vuelta solo cambia lo que viene después.
        assertEquals(sinVuelta.visitas(), conVuelta.visitas());
    }

    @Test
    void sinGuiasNoHayVisitas() {
        OptimizadorRutas.Plan plan = optimizador.optimizar(FECHA, SALIDA, true, List.of());

        assertEquals(List.of(), plan.visitas());
        assertEquals(0.0, plan.kilometros());
    }

    @Test
    void unaGuiaSinCoordenadasEsUnErrorDeProgramacion() {
        Guia sinUbicar = guiaAlSur(1, 1);
        sinUbicar.setLatitud(null);

        assertThrows(IllegalStateException.class, () -> optimizar(List.of(sinUbicar)));
    }

    @Test
    void laDistanciaEntreDosPuntosDeSantiago() {
        // Casi sobre el mismo paralelo: 0,016 grados de longitud a esta latitud son ~1,49 km.
        double km = OptimizadorLocal.kilometros(-33.4378, -70.6505, -33.4372, -70.6345);

        assertEquals(1.49, km, 0.05);
    }

    private static double minutosEntre(Instant desde, Instant hasta) {
        return Duration.between(desde, hasta).toSeconds() / 60.0;
    }
}
