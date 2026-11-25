package com.calimport.guias.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.EstadoCorreo;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;

import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Estos tests corren contra H2 con el esquema generado por Hibernate desde las entidades:
 * verifican el mapeo real, no un mock del repositorio.
 */
@SpringBootTest
@Transactional
class GuiaRepositoryTest {

    @Autowired
    private GuiaRepository guiaRepository;

    @Autowired
    private EntityManager entityManager;

    private Guia nuevaGuia(int docEntry, long folio) {
        return new Guia(docEntry, folio, "Cliente " + docEntry, "Direccion " + docEntry);
    }

    @Test
    void unaGuiaReciennCreadaSePersisteConEstadoPendiente() {
        Guia guardada = guiaRepository.saveAndFlush(nuevaGuia(1001, 5555L));

        assertTrue(guardada.getId() != null && guardada.getId() > 0, "el id lo genera la BD");
        assertEquals(EstadoGuia.PENDIENTE, guardada.getEstado());
    }

    @Test
    void losCamposDelCicloDeVidaAceptanNullAlCrearLaGuia() {
        // Si estas columnas fueran NOT NULL, el INSERT inicial sería imposible: la guía
        // nace sin repartidor, sin fecha de entrega y sin evidencia.
        Guia guardada = guiaRepository.saveAndFlush(nuevaGuia(1002, 5556L));

        assertNull(guardada.getRepartidorId());
        assertNull(guardada.getFechaRecepcionRepartidor());
        assertNull(guardada.getFechaEntrega());
        assertNull(guardada.getUrlFoto());
        assertNull(guardada.getHashFoto());
    }

    @Test
    void elEstadoSeGuardaComoTextoYNoComoOrdinal() {
        // Con ordinal, reordenar el enum corrompería las filas ya guardadas.
        Guia guardada = guiaRepository.saveAndFlush(nuevaGuia(1003, 5557L));

        Object estadoEnBd = entityManager
                .createNativeQuery("SELECT estado FROM guia WHERE id = :id")
                .setParameter("id", guardada.getId())
                .getSingleResult();

        assertEquals("PENDIENTE", String.valueOf(estadoEnBd));
    }

    @Test
    void findByDocEntryEncuentraLaGuiaQueVinoDeSap() {
        guiaRepository.saveAndFlush(nuevaGuia(1004, 5558L));

        Optional<Guia> encontrada = guiaRepository.findByDocEntry(1004);

        assertTrue(encontrada.isPresent());
        assertEquals(5558L, encontrada.get().getFolio());
    }

    @Test
    void findByDocEntryDevuelveVacioSiEsaGuiaNoFueImportada() {
        assertTrue(guiaRepository.findByDocEntry(999999).isEmpty());
    }

    @Test
    void findByEstadoSeparaLasPendientesDeLasResueltas() {
        guiaRepository.save(nuevaGuia(1005, 5559L));

        Guia entregada = nuevaGuia(1006, 5560L);
        entregada.setEstado(EstadoGuia.ENTREGADA);
        guiaRepository.save(entregada);

        Guia rechazada = nuevaGuia(1007, 5561L);
        rechazada.setEstado(EstadoGuia.RECHAZADA);
        guiaRepository.saveAndFlush(rechazada);

        assertEquals(1, guiaRepository.findByEstado(EstadoGuia.PENDIENTE).size());
        assertEquals(1, guiaRepository.findByEstado(EstadoGuia.ENTREGADA).size());
        assertEquals(1, guiaRepository.findByEstado(EstadoGuia.RECHAZADA).size());
    }

    @Test
    void findByRepartidorIdDevuelveSoloLasGuiasDeEseRepartidor() {
        Guia deJuan = nuevaGuia(1008, 5562L);
        deJuan.setRepartidorId(7);
        guiaRepository.save(deJuan);

        Guia dePedro = nuevaGuia(1009, 5563L);
        dePedro.setRepartidorId(8);
        guiaRepository.save(dePedro);

        guiaRepository.saveAndFlush(nuevaGuia(1010, 5564L)); // sin asignar

        List<Guia> guiasDeJuan = guiaRepository.findByRepartidorId(7);

        assertEquals(1, guiasDeJuan.size());
        assertEquals(1008, guiasDeJuan.get(0).getDocEntry());
    }

    @Test
    void noSePuedeGuardarUnaGuiaSinClienteNiDireccion() {
        Guia sinDatos = new Guia(1011, 5565L, null, null);

        assertThrows(ConstraintViolationException.class, () -> guiaRepository.saveAndFlush(sinDatos));
    }

    @Test
    void noSePuedeGuardarUnaGuiaSinFolio() {
        Guia sinFolio = new Guia(1012, null, "Cliente", "Direccion");

        assertThrows(ConstraintViolationException.class, () -> guiaRepository.saveAndFlush(sinFolio));
    }

    // --- correo de despacho ---

    private Long entregadaConCorreoPorEnviar(int docEntry, long folio) {
        Guia guia = nuevaGuia(docEntry, folio);
        guia.setEstado(EstadoGuia.ENTREGADA);
        guia.setSincronizada(false);
        guia.setCorreoEstado(EstadoCorreo.PENDIENTE);
        Long id = guiaRepository.saveAndFlush(guia).getId();
        entityManager.clear();
        return id;
    }

    @Test
    void unaGuiaNuevaNoTieneCorreoQueEnviar() {
        // Null y no PENDIENTE: las guías anteriores al correo no deben salir todas de golpe.
        Guia guardada = guiaRepository.saveAndFlush(nuevaGuia(1013, 5567L));

        assertNull(guardada.getCorreoEstado());
        assertEquals(0, guardada.getCorreoIntentos());
        assertTrue(guiaRepository.findByCorreoEstado(EstadoCorreo.PENDIENTE).isEmpty());
    }

    @Test
    void guardarElCorreoSoloEscribeSusColumnas() {
        Long id = entregadaConCorreoPorEnviar(1014, 5568L);
        assertEquals(1, guiaRepository.findByCorreoEstado(EstadoCorreo.PENDIENTE).size());

        // La copia que tiene el proceso de correo: en lo demás puede estar vieja.
        Guia delProceso = guiaRepository.findById(id).orElseThrow();
        entityManager.detach(delProceso);
        delProceso.setSincronizada(true);
        delProceso.setCorreoEstado(EstadoCorreo.ENVIADO);
        delProceso.setCorreoDestino("compras@cliente.cl");
        delProceso.setCorreoIntentos(1);
        delProceso.setCorreoFecha(Instant.parse("2026-11-19T15:00:00Z"));
        guiaRepository.guardarCorreo(delProceso);
        entityManager.clear();

        Guia leida = guiaRepository.findById(id).orElseThrow();
        assertEquals(EstadoCorreo.ENVIADO, leida.getCorreoEstado());
        assertEquals("compras@cliente.cl", leida.getCorreoDestino());
        assertEquals(1, leida.getCorreoIntentos());
        assertEquals(Instant.parse("2026-11-19T15:00:00Z"), leida.getCorreoFecha());
        assertFalse(leida.isSincronizada(), "lo que no es del correo no lo escribe");
    }

    @Test
    void otroCambioALaGuiaNoDevuelveElCorreoAPendiente() {
        // Bodega reintenta el envío a SAP con la guía ya leída, justo cuando el proceso de
        // correo la envía. Si ese guardado escribiera todas las columnas, devolvería el correo
        // a PENDIENTE y el cliente lo recibiría dos veces.
        Long id = entregadaConCorreoPorEnviar(1015, 5569L);
        Guia enLaRequest = guiaRepository.findById(id).orElseThrow();

        guiaRepository.actualizarCorreo(id, EstadoCorreo.ENVIADO, "compras@cliente.cl", 1,
                Instant.parse("2026-11-19T15:00:00Z"), null);
        enLaRequest.setSincronizada(true);
        entityManager.flush();
        entityManager.clear();

        Guia leida = guiaRepository.findById(id).orElseThrow();
        assertEquals(EstadoCorreo.ENVIADO, leida.getCorreoEstado());
        assertTrue(leida.isSincronizada());
    }
}
