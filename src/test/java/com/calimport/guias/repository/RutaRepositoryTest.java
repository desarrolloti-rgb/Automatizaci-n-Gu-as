package com.calimport.guias.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.Guia;
import com.calimport.guias.model.Ruta;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Ruta ruta = new Ruta(repartidorId, FECHA, LocalTime.of(9, 0));
        int orden = 1;
        for (Guia g : guias) {
            ruta.agregarParada(g, orden++, Instant.now());
        }
        rutaRepository.save(ruta);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void regenerarLaRutaDelDiaReemplazaLaAnteriorSinChocarConLasRestricciones() {
        Guia a = guia(2001);
        Guia b = guia(2002);
        guardarRuta(7, List.of(a, b));

        rutaRepository.borrarParadasPrevias(List.of(b.getId(), a.getId()), 7, FECHA);
        rutaRepository.borrarRuta(7, FECHA);
        guardarRuta(7, List.of(guiaRepository.findById(b.getId()).orElseThrow(),
                guiaRepository.findById(a.getId()).orElseThrow()));

        Ruta nueva = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        assertEquals(1, rutaRepository.count());
        assertEquals(b.getId(), nueva.getParadas().get(0).getGuia().getId());
        assertEquals(a.getId(), nueva.getParadas().get(1).getGuia().getId());
    }

    @Test
    void reasignarUnaGuiaLaSacaDeLaRutaDeOtroRepartidor() {
        Guia a = guia(2003);
        Guia b = guia(2004);
        guardarRuta(7, List.of(a, b));

        rutaRepository.borrarParadasPrevias(List.of(b.getId()), 8, FECHA);
        rutaRepository.borrarRuta(8, FECHA);
        guardarRuta(8, List.of(guiaRepository.findById(b.getId()).orElseThrow()));

        Ruta deJuan = rutaRepository.findByRepartidorIdAndFecha(7, FECHA).orElseThrow();
        assertEquals(List.of(a.getId()), deJuan.getParadas().stream().map(p -> p.getGuia().getId()).toList());
        assertTrue(rutaRepository.findByRepartidorIdAndFecha(8, FECHA).isPresent());
    }
}
