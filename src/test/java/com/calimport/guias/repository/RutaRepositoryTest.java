package com.calimport.guias.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.Ruta;
import com.calimport.guias.model.RutaParada;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contra H2 con las migraciones de Flyway: lo que importa acá son las restricciones UNIQUE. */
@SpringBootTest
@Transactional
class RutaRepositoryTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 9, 15);

    @Autowired
    private RutaRepository rutaRepository;

    @Autowired
    private GuiaRepository guiaRepository;

    @Autowired
    private EntityManager entityManager;

    private Guia guia(int docEntry) {
        return guiaRepository.save(new Guia(docEntry, 5000L + docEntry, "Cliente", "Direccion"));
    }

    private void guardarRuta(int repartidorId, List<Guia> guias) {
        guardarRuta(repartidorId, FECHA, guias);
    }

    private void guardarRuta(int repartidorId, LocalDate fecha, List<Guia> guias) {
        Ruta ruta = new Ruta(repartidorId, fecha, LocalTime.of(9, 0));
        int orden = 1;
        for (Guia g : guias) {
            ruta.agregarParada(g, orden++, Instant.now());
        }
        rutaRepository.save(ruta);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void regenerarReplanificaLaMismaRutaSinChocarConLasRestricciones() {
        // La ruta es lo que se mide: regenerarla no la borra, le cambia el plan y las paradas.
        Guia a = guia(2001);
        Guia b = guia(2002);
        guardarRuta(7, List.of(a, b));
        Long id = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow().getId();

        rutaRepository.borrarParadasPrevias(List.of(b.getId(), a.getId()), 7, FECHA);
        Ruta ruta = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        Instant termino = Instant.parse("2026-09-15T16:10:00Z");
        ruta.planificar(LocalTime.of(10, 0), true, 23.5, termino);
        ruta.agregarParada(guiaRepository.findById(b.getId()).orElseThrow(), 1, Instant.now());
        ruta.agregarParada(guiaRepository.findById(a.getId()).orElseThrow(), 2, Instant.now());
        rutaRepository.save(ruta);
        entityManager.flush();
        entityManager.clear();

        Ruta nueva = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        assertEquals(1, rutaRepository.count());
        assertEquals(id, nueva.getId());
        assertEquals(b.getId(), nueva.getParadas().get(0).getGuia().getId());
        assertEquals(a.getId(), nueva.getParadas().get(1).getGuia().getId());
        assertEquals(LocalTime.of(10, 0), nueva.getHoraSalida());
        assertTrue(nueva.isVuelveABodega());
        assertEquals(23.5, nueva.getKmPlanificados());
        assertEquals(termino, nueva.getTerminoEstimado());
    }

    @Test
    void unaRutaSinPlanNoVuelveYNoTieneKm() {
        guardarRuta(7, List.of(guia(2005)));

        Ruta ruta = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        assertFalse(ruta.isVuelveABodega());
        assertNull(ruta.getKmPlanificados());
        assertNull(ruta.getTerminoEstimado());
    }

    @Test
    void elOdometroAnotadoSeGuardaConCuandoSeAnoto() {
        guardarRuta(7, List.of(guia(2006)));
        Ruta ruta = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        Instant alSalir = Instant.parse("2026-09-15T12:25:00Z");
        Instant alTerminar = Instant.parse("2026-09-15T20:40:00Z");
        ruta.anotarOdometroSalida(12340, alSalir);
        ruta.anotarOdometroTermino(12403, alTerminar);
        rutaRepository.save(ruta);
        entityManager.flush();
        entityManager.clear();

        Ruta leida = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        assertEquals(12340, leida.getOdometroSalida());
        assertEquals(alSalir, leida.getOdometroSalidaEn());
        assertEquals(12403, leida.getOdometroTermino());
        assertEquals(alTerminar, leida.getOdometroTerminoEn());
    }

    @Test
    void reasignarUnaGuiaLaSacaDeLaRutaDeOtroRepartidor() {
        Guia a = guia(2003);
        Guia b = guia(2004);
        guardarRuta(7, List.of(a, b));

        rutaRepository.borrarParadasPrevias(List.of(b.getId()), 8, FECHA);
        guardarRuta(8, List.of(guiaRepository.findById(b.getId()).orElseThrow()));

        Ruta deJuan = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        assertEquals(List.of(a.getId()), deJuan.getParadas().stream().map(p -> p.getGuia().getId()).toList());
        assertTrue(rutaRepository.findByRepartidorIdAndFecha(8, FECHA).isPresent());
    }

    // --- Historial: el recorrido de un día pasado no se reescribe -----------------------------

    private static final LocalDate AYER = FECHA.minusDays(1);

    private List<Long> guiasDe(int repartidorId, LocalDate fecha) {
        return rutaRepository.findByRepartidorIdAndFecha(repartidorId, fecha).orElseThrow()
                .getParadas().stream().map(p -> p.getGuia().getId()).toList();
    }

    @Test
    void unaGuiaQueVuelveASalirQuedaEnLaRutaDeAyerYEnLaDeHoy() {
        // Ayer no se pudo entregar; hoy sale de nuevo. El recorrido de ayer es historia.
        Guia a = guia(2010);
        guardarRuta(7, AYER, List.of(a));

        rutaRepository.borrarParadasPrevias(List.of(a.getId()), 7, FECHA);
        guardarRuta(7, FECHA, List.of(guiaRepository.findById(a.getId()).orElseThrow()));

        assertEquals(List.of(a.getId()), guiasDe(7, AYER));
        assertEquals(List.of(a.getId()), guiasDe(7, FECHA));
    }

    @Test
    void sacarUnaGuiaDeLasRutasNoTocaLasDeDiasAnteriores() {
        Guia a = guia(2011);
        Guia b = guia(2012);
        guardarRuta(7, AYER, List.of(a));
        guardarRuta(7, FECHA, List.of(a, b));

        rutaRepository.quitarGuiaDeRutas(a.getId(), FECHA);

        assertEquals(List.of(a.getId()), guiasDe(7, AYER));
        assertEquals(List.of(b.getId()), guiasDe(7, FECHA));
    }

    @Test
    void unaParadaYaResueltaNoSeBorraNiAlReasignarNiAlRegenerar() {
        // Rechazada esta mañana y reabierta: lo que pasó en esa parada queda en la ruta de hoy.
        Guia a = guia(2013);
        Guia b = guia(2014);
        guardarRuta(7, FECHA, List.of(a, b));
        Ruta ruta = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        Instant cuando = Instant.parse("2026-09-15T14:00:00Z");
        ruta.getParadas().get(0).resolver(EstadoGuia.RECHAZADA, cuando);
        rutaRepository.save(ruta);
        entityManager.flush();
        entityManager.clear();

        rutaRepository.quitarGuiaDeRutas(a.getId(), FECHA);
        rutaRepository.borrarParadasPrevias(List.of(b.getId()), 7, FECHA);

        RutaParada queda = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow().getParadas().get(0);
        assertEquals(1, rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow().getParadas().size());
        assertEquals(a.getId(), queda.getGuia().getId());
        assertEquals(EstadoGuia.RECHAZADA, queda.getResultado());
        assertEquals(cuando, queda.getResueltaEn());
    }

    @Test
    void laMismaGuiaPuedeSalirDosVecesElMismoDia() {
        // Rechazada, reabierta y devuelta al mismo repartidor: la ruta muestra los dos intentos.
        Guia a = guia(2015);
        guardarRuta(7, FECHA, List.of(a));
        Ruta ruta = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        ruta.getParadas().get(0).resolver(EstadoGuia.RECHAZADA, Instant.parse("2026-09-15T14:00:00Z"));
        ruta.agregarParada(guiaRepository.findById(a.getId()).orElseThrow(), 2, Instant.now());
        rutaRepository.save(ruta);
        entityManager.flush();
        entityManager.clear();

        assertEquals(List.of(a.getId(), a.getId()), guiasDe(7, FECHA));
    }
}
