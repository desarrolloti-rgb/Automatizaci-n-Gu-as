package com.calimport.guias.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.calimport.guias.model.Repartidor;
import com.calimport.guias.repository.RepartidorRepository;
import com.calimport.guias.utils.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepartidorServiceTest {

    @Mock
    private RepartidorRepository repartidorRepository;

    private RepartidorService service;

    @BeforeEach
    void setUp() {
        service = new RepartidorService(repartidorRepository);
    }

    private Repartidor repartidor(int employeeId, String nombre, String email, boolean activo) {
        Repartidor repartidor = new Repartidor();
        repartidor.setEmployeeId(employeeId);
        repartidor.setNombre(nombre);
        repartidor.setEmail(email);
        repartidor.setActivo(activo);
        return repartidor;
    }

    private void devuelveLoQueGuarda() {
        when(repartidorRepository.save(any(Repartidor.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void obtenerPorIdDevuelveElRepartidorCuandoExiste() {
        Repartidor esperado = repartidor(7, "Juan Perez", "juan@calimport.cl", true);
        when(repartidorRepository.findById(7)).thenReturn(Optional.of(esperado));

        assertSame(esperado, service.obtenerPorId(7));
    }

    @Test
    void obtenerPorIdLanzaNotFoundCuandoNoExiste() {
        when(repartidorRepository.findById(99)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.obtenerPorId(99));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void listarActivosExcluyeALosInactivos() {
        List<Repartidor> activos = List.of(repartidor(7, "Juan Perez", "juan@calimport.cl", true));
        when(repartidorRepository.findByActivoTrue()).thenReturn(activos);

        assertEquals(activos, service.listarActivos());
    }

    @Test
    void upsertCreaElRepartidorCuandoNoHabiaCopiaLocal() {
        when(repartidorRepository.findById(7)).thenReturn(Optional.empty());
        devuelveLoQueGuarda();

        Repartidor resultado = service.upsertDesdeSap(7, "Juan Perez", "juan@calimport.cl", true);

        assertEquals(7, resultado.getEmployeeId());
        assertEquals("Juan Perez", resultado.getNombre());
        assertEquals("juan@calimport.cl", resultado.getEmail());
        assertTrue(resultado.isActivo());
    }

    @Test
    void upsertActualizaLaFilaExistenteEnLugarDeInsertarOtra() {
        Repartidor existente = repartidor(7, "Nombre Viejo", "viejo@calimport.cl", false);
        when(repartidorRepository.findById(7)).thenReturn(Optional.of(existente));
        devuelveLoQueGuarda();

        Repartidor resultado = service.upsertDesdeSap(7, "Nombre Nuevo", "nuevo@calimport.cl", true);

        // Reusar la instancia encontrada es lo que hace que JPA emita UPDATE y no INSERT.
        ArgumentCaptor<Repartidor> guardado = ArgumentCaptor.forClass(Repartidor.class);
        verify(repartidorRepository).save(guardado.capture());
        assertSame(existente, guardado.getValue());

        assertEquals("Nombre Nuevo", resultado.getNombre());
        assertEquals("nuevo@calimport.cl", resultado.getEmail());
        assertTrue(resultado.isActivo());
    }

    @Test
    void upsertPropagaLaBajaCuandoSapMarcaAlRepartidorComoInactivo() {
        Repartidor existente = repartidor(7, "Juan Perez", "juan@calimport.cl", true);
        when(repartidorRepository.findById(7)).thenReturn(Optional.of(existente));
        devuelveLoQueGuarda();

        Repartidor resultado = service.upsertDesdeSap(7, "Juan Perez", "juan@calimport.cl", false);

        assertFalse(resultado.isActivo());
    }
}
