package com.calimport.guias.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.utils.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuiaServiceTest {

    private static final int DOC_ENTRY = 1001;
    private static final long FOLIO = 5555L;

    @Mock
    private GuiaRepository guiaRepository;

    private GuiaService service;

    @BeforeEach
    void setUp() {
        service = new GuiaService(guiaRepository);
    }

    /** Guía tal como queda recién creada: PENDIENTE, sin repartidor ni evidencia. */
    private Guia guiaPendiente() {
        Guia guia = new Guia(DOC_ENTRY, FOLIO, "Cliente X", "Av. Siempre Viva 742");
        guia.setId(1L);
        return guia;
    }

    private Guia guiaConRepartidor() {
        Guia guia = guiaPendiente();
        guia.setRepartidorId(7);
        return guia;
    }

    /** save() en JPA devuelve la entidad persistida: se emula devolviendo la misma instancia. */
    private void devuelveLoQueGuarda() {
        when(guiaRepository.save(any(Guia.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- crear ---

    @Test
    void crearGuardaLaGuiaConElEstadoInicialCuandoElDocEntryNoExiste() {
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.empty());
        devuelveLoQueGuarda();

        Guia creada = service.crear(DOC_ENTRY, FOLIO, "Cliente X", "Av. Siempre Viva 742");

        assertEquals(DOC_ENTRY, creada.getDocEntry());
        assertEquals(FOLIO, creada.getFolio());
        assertEquals("Cliente X", creada.getCliente());
        assertEquals("Av. Siempre Viva 742", creada.getDireccion());
        // El resto del ciclo de vida arranca vacío: es lo que permite que el INSERT pase.
        assertEquals(EstadoGuia.PENDIENTE, creada.getEstado());
        assertNull(creada.getRepartidorId());
        assertNull(creada.getFechaEntrega());
        assertNull(creada.getUrlFoto());
        assertNull(creada.getHashFoto());
        assertFalse(creada.isRecibidaPorRepartidor());
        assertFalse(creada.isSincronizada());
    }

    @Test
    void crearRechazaConConflictSiYaExisteUnaGuiaParaEseDocEntry() {
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(guiaPendiente()));

        ApiException e = assertThrows(ApiException.class,
                () -> service.crear(DOC_ENTRY, FOLIO, "Cliente X", "Direccion"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    // --- lecturas ---

    @Test
    void obtenerPorIdDevuelveLaGuiaCuandoExiste() {
        Guia guia = guiaPendiente();
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guia));

        assertSame(guia, service.obtenerPorId(1L));
    }

    @Test
    void obtenerPorIdLanzaNotFoundCuandoNoExiste() {
        when(guiaRepository.findById(99L)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.obtenerPorId(99L));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void listarPorEstadoConsultaSoloEseEstado() {
        List<Guia> esperadas = List.of(guiaPendiente());
        when(guiaRepository.findByEstado(EstadoGuia.PENDIENTE)).thenReturn(esperadas);

        assertEquals(esperadas, service.listarPorEstado(EstadoGuia.PENDIENTE));
    }

    @Test
    void listarPorRepartidorConsultaSoloEseRepartidor() {
        List<Guia> esperadas = List.of(guiaConRepartidor());
        when(guiaRepository.findByRepartidorId(7)).thenReturn(esperadas);

        assertEquals(esperadas, service.listarPorRepartidor(7));
    }

    // --- asignarRepartidor ---

    @Test
    void asignarRepartidorGuardaElIdCuandoLaGuiaEstaPendiente() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaPendiente()));
        devuelveLoQueGuarda();

        Guia resultado = service.asignarRepartidor(1L, 7);

        assertEquals(7, resultado.getRepartidorId());
        assertEquals(EstadoGuia.PENDIENTE, resultado.getEstado());
    }

    @Test
    void asignarRepartidorRechazaConBadRequestSiNoVianeElId() {
        ApiException e = assertThrows(ApiException.class, () -> service.asignarRepartidor(1L, null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        // Se valida antes de tocar la BD: ni siquiera busca la guía.
        verify(guiaRepository, never()).findById(any());
    }

    @Test
    void asignarRepartidorRechazaConConflictSiLaGuiaYaFueResuelta() {
        Guia entregada = guiaPendiente();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        ApiException e = assertThrows(ApiException.class, () -> service.asignarRepartidor(1L, 7));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    // --- marcarRecibidaPorRepartidor ---

    @Test
    void marcarRecibidaDejaConstanciaDeLaFechaDeRetiro() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));
        devuelveLoQueGuarda();

        Instant antes = Instant.now();
        Guia resultado = service.marcarRecibidaPorRepartidor(1L);
        Instant despues = Instant.now();

        assertTrue(resultado.isRecibidaPorRepartidor());
        assertNotNull(resultado.getFechaRecepcionRepartidor());
        assertFalse(resultado.getFechaRecepcionRepartidor().isBefore(antes));
        assertFalse(resultado.getFechaRecepcionRepartidor().isAfter(despues));
        // Retirar la guía no la resuelve: sigue PENDIENTE hasta entregar o rechazar.
        assertEquals(EstadoGuia.PENDIENTE, resultado.getEstado());
    }

    @Test
    void marcarRecibidaRechazaConConflictSiNoHayRepartidorAsignado() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaPendiente()));

        ApiException e = assertThrows(ApiException.class, () -> service.marcarRecibidaPorRepartidor(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    @Test
    void marcarRecibidaRechazaConConflictSiLaGuiaYaFueResuelta() {
        Guia rechazada = guiaConRepartidor();
        rechazada.setEstado(EstadoGuia.RECHAZADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(rechazada));

        ApiException e = assertThrows(ApiException.class, () -> service.marcarRecibidaPorRepartidor(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    // --- entregar ---

    @Test
    void entregarGuardaEstadoFechaYEvidenciaFotografica() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));
        devuelveLoQueGuarda();

        Instant antes = Instant.now();
        Guia resultado = service.entregar(1L, "https://fotos/guia-1.jpg", "abc123hash");
        Instant despues = Instant.now();

        assertEquals(EstadoGuia.ENTREGADA, resultado.getEstado());
        assertEquals("https://fotos/guia-1.jpg", resultado.getUrlFoto());
        assertEquals("abc123hash", resultado.getHashFoto());
        assertNotNull(resultado.getFechaEntrega());
        assertFalse(resultado.getFechaEntrega().isBefore(antes));
        assertFalse(resultado.getFechaEntrega().isAfter(despues));
        // La entrega no se marca sincronizada sola: eso lo hace el push a SAP.
        assertFalse(resultado.isSincronizada());
    }

    @Test
    void entregarExigeUrlDeFoto() {
        ApiException sinUrl = assertThrows(ApiException.class, () -> service.entregar(1L, null, "hash"));
        ApiException urlEnBlanco = assertThrows(ApiException.class, () -> service.entregar(1L, "   ", "hash"));

        assertEquals(HttpStatus.BAD_REQUEST, sinUrl.getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, urlEnBlanco.getStatus());
        verify(guiaRepository, never()).findById(any());
    }

    @Test
    void entregarExigeHashDeFoto() {
        ApiException sinHash = assertThrows(ApiException.class, () -> service.entregar(1L, "https://f/1.jpg", null));
        ApiException hashEnBlanco = assertThrows(ApiException.class, () -> service.entregar(1L, "https://f/1.jpg", "  "));

        assertEquals(HttpStatus.BAD_REQUEST, sinHash.getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, hashEnBlanco.getStatus());
        verify(guiaRepository, never()).findById(any());
    }

    @Test
    void entregarRechazaConConflictUnaGuiaYaEntregada() {
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        ApiException e = assertThrows(ApiException.class,
                () -> service.entregar(1L, "https://fotos/otra.jpg", "otrohash"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    // --- rechazar ---

    @Test
    void rechazarMarcaLaGuiaSinExigirEvidenciaFotografica() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));
        devuelveLoQueGuarda();

        Guia resultado = service.rechazar(1L);

        assertEquals(EstadoGuia.RECHAZADA, resultado.getEstado());
        assertNotNull(resultado.getFechaEntrega());
        // Un rechazo no tiene foto de recepción: los campos quedan vacíos a propósito.
        assertNull(resultado.getUrlFoto());
        assertNull(resultado.getHashFoto());
    }

    @Test
    void rechazarRechazaConConflictUnaGuiaYaResuelta() {
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        ApiException e = assertThrows(ApiException.class, () -> service.rechazar(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    // --- marcarSincronizada ---

    @Test
    void marcarSincronizadaLevantaElFlagSinTocarElEstado() {
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));
        devuelveLoQueGuarda();

        Guia resultado = service.marcarSincronizada(1L);

        assertTrue(resultado.isSincronizada());
        assertEquals(EstadoGuia.ENTREGADA, resultado.getEstado());
    }
}
