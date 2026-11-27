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
import com.calimport.guias.model.Rol;
import com.calimport.guias.repository.RepartidorRepository;
import com.calimport.guias.sap.EmpleadoSap;
import com.calimport.guias.service.RepartidorService.CambiosRepartidores;
import com.calimport.guias.utils.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    void listarActivosTraeSoloRepartidoresActivosYNoAlJefeDeBodega() {
        // El jefe de bodega también está en la tabla, pero no recibe guías.
        List<Repartidor> activos = List.of(repartidor(7, "Juan Perez", "juan@calimport.cl", true));
        when(repartidorRepository.findByActivoTrueAndRol(Rol.Despachador)).thenReturn(activos);

        assertEquals(activos, service.listarActivos());
    }

    @Test
    void upsertCreaElRepartidorCuandoNoHabiaCopiaLocal() {
        when(repartidorRepository.findById(7)).thenReturn(Optional.empty());
        devuelveLoQueGuarda();

        Repartidor resultado = service.upsertDesdeSap(7, "Juan Perez", "juan@calimport.cl", true, Rol.Despachador           );

        assertEquals(7, resultado.getEmployeeId());
        assertEquals("Juan Perez", resultado.getNombre());
        assertEquals("juan@calimport.cl", resultado.getEmail());
        assertTrue(resultado.isActivo());
        assertEquals(Rol.Despachador, resultado.getRol());
    }

    @Test
    void upsertRefrescaElRolEnCadaLogin() {
        Repartidor existente = repartidor(9, "Pedro", "pedro@calimport.cl", true);
        when(repartidorRepository.findById(9)).thenReturn(Optional.of(existente));
        devuelveLoQueGuarda();

        Repartidor resultado = service.upsertDesdeSap(9, "Pedro", "pedro@calimport.cl", true, Rol.JEFE_BODEGA);

        assertEquals(Rol.JEFE_BODEGA, resultado.getRol());
    }

    @Test
    void upsertActualizaLaFilaExistenteEnLugarDeInsertarOtra() {
        Repartidor existente = repartidor(7, "Nombre Viejo", "viejo@calimport.cl", false);
        when(repartidorRepository.findById(7)).thenReturn(Optional.of(existente));
        devuelveLoQueGuarda();

        Repartidor resultado = service.upsertDesdeSap(7, "Nombre Nuevo", "nuevo@calimport.cl", true, Rol.Despachador            );

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

        Repartidor resultado = service.upsertDesdeSap(7, "Juan Perez", "juan@calimport.cl", false, Rol.Despachador);

        assertFalse(resultado.isActivo());
    }

    @Test
    void elLoginNoTocaSiElRepartidorVuelveABodega() {
        // No viene de SAP: lo define bodega al generar sus rutas.
        Repartidor existente = repartidor(7, "Juan Perez", "juan@calimport.cl", true);
        existente.setVuelveABodega(true);
        when(repartidorRepository.findById(7)).thenReturn(Optional.of(existente));
        devuelveLoQueGuarda();

        Repartidor resultado = service.upsertDesdeSap(7, "Juan Perez", "juan@calimport.cl", true, Rol.Despachador);

        assertTrue(resultado.isVuelveABodega());
    }

    @Test
    void recordarLaVueltaLaDejaComoLoHabitual() {
        Repartidor existente = repartidor(7, "Juan Perez", "juan@calimport.cl", true);
        when(repartidorRepository.findById(7)).thenReturn(Optional.of(existente));

        service.recordarVuelta(7, true);

        assertTrue(existente.isVuelveABodega());
        verify(repartidorRepository).save(existente);
    }

    // --- sincronizar la lista con EmployeesInfo de SAP ---

    private static EmpleadoSap empleado(int id, String nombre, String email, String cargo, boolean activo) {
        return new EmpleadoSap(id, nombre, email, cargo, activo);
    }

    private Repartidor conRol(Repartidor repartidor, Rol rol) {
        repartidor.setRol(rol);
        return repartidor;
    }

    @Test
    void sincronizarDaDeAltaAlDespachadorQueNuncaInicioSesion() {
        when(repartidorRepository.findAll()).thenReturn(List.of());
        devuelveLoQueGuarda();

        CambiosRepartidores cambios = service.sincronizarConSap(List.of(
                empleado(31, "Ana Soto", "ana@calimport.cl", "Bodeguera ANT", true)));

        ArgumentCaptor<Repartidor> guardado = ArgumentCaptor.forClass(Repartidor.class);
        verify(repartidorRepository).save(guardado.capture());
        assertEquals(31, guardado.getValue().getEmployeeId());
        assertEquals("ana@calimport.cl", guardado.getValue().getEmail());
        assertTrue(guardado.getValue().isActivo());
        assertEquals(Rol.Despachador, guardado.getValue().getRol());
        assertEquals(List.of("Ana Soto"), cambios.altas());
        assertEquals(List.of(), cambios.bajas());
    }

    @Test
    void sincronizarDaDeBajaAlQueSapMarcaInactivo() {
        Repartidor juan = repartidor(7, "Juan Perez", "juan@calimport.cl", true);
        when(repartidorRepository.findAll()).thenReturn(List.of(juan));
        devuelveLoQueGuarda();

        CambiosRepartidores cambios = service.sincronizarConSap(List.of(
                empleado(7, "Juan Perez", "juan@calimport.cl", "Bodeguero", false),
                empleado(8, "Otro", "otro@calimport.cl", "Bodeguero", true)));

        assertFalse(juan.isActivo());
        assertEquals(List.of("Juan Perez"), cambios.bajas());
    }

    @Test
    void sincronizarDaDeBajaAlQueCambioDeCargoOYaNoEstaEnSap() {
        Repartidor juan = repartidor(7, "Juan Perez", "juan@calimport.cl", true);
        Repartidor pedro = repartidor(8, "Pedro", "pedro@calimport.cl", true);
        when(repartidorRepository.findAll()).thenReturn(List.of(juan, pedro));
        devuelveLoQueGuarda();

        CambiosRepartidores cambios = service.sincronizarConSap(List.of(
                empleado(7, "Juan Perez", "juan@calimport.cl", "Ventas Terreno", true),
                empleado(40, "Jefe", "jefe@calimport.cl", "Jefe Bodega", true)));

        assertFalse(juan.isActivo());
        assertFalse(pedro.isActivo());
        assertEquals(List.of("Juan Perez", "Pedro"), cambios.bajas());
    }

    @Test
    void sincronizarNoReescribeLoQueNoCambioYConservaLaVuelta() {
        Repartidor juan = repartidor(7, "Juan Perez", "juan@calimport.cl", true);
        juan.setVuelveABodega(true);
        when(repartidorRepository.findAll()).thenReturn(List.of(juan));

        CambiosRepartidores cambios = service.sincronizarConSap(List.of(
                empleado(7, "Juan Perez", "juan@calimport.cl", "Bodeguero", true)));

        verify(repartidorRepository, never()).save(any());
        assertTrue(juan.isVuelveABodega());
        assertTrue(cambios.altas().isEmpty() && cambios.bajas().isEmpty());
    }

    @Test
    void sincronizarRefrescaNombreYRolSinContarloComoAlta() {
        Repartidor juan = repartidor(7, "Juan", "juan@calimport.cl", true);
        when(repartidorRepository.findAll()).thenReturn(List.of(juan));
        devuelveLoQueGuarda();

        CambiosRepartidores cambios = service.sincronizarConSap(List.of(
                empleado(7, "Juan Perez", "juan@calimport.cl", "Despachador", true)));

        assertEquals("Juan Perez", juan.getNombre());
        assertTrue(cambios.altas().isEmpty());
    }

    @Test
    void elJefeDeBodegaQuedaAlDiaPeroNoEsAltaDeRepartidor() {
        when(repartidorRepository.findAll()).thenReturn(List.of());
        devuelveLoQueGuarda();

        CambiosRepartidores cambios = service.sincronizarConSap(List.of(
                empleado(40, "Jefe", "jefe@calimport.cl", "Jefe Bodega", true)));

        verify(repartidorRepository).save(any());
        assertTrue(cambios.altas().isEmpty());
    }

    @Test
    void reactivarAUnDespachadorEsUnaAlta() {
        Repartidor juan = repartidor(7, "Juan Perez", "juan@calimport.cl", false);
        when(repartidorRepository.findAll()).thenReturn(List.of(juan));
        devuelveLoQueGuarda();

        CambiosRepartidores cambios = service.sincronizarConSap(List.of(
                empleado(7, "Juan Perez", "juan@calimport.cl", "Bodeguero", true)));

        assertTrue(juan.isActivo());
        assertEquals(List.of("Juan Perez"), cambios.altas());
    }

    @Test
    void unJefeQuePasaADespachadorEsUnaAlta() {
        Repartidor ana = conRol(repartidor(31, "Ana", "ana@calimport.cl", true), Rol.JEFE_BODEGA);
        when(repartidorRepository.findAll()).thenReturn(List.of(ana));
        devuelveLoQueGuarda();

        CambiosRepartidores cambios = service.sincronizarConSap(List.of(
                empleado(31, "Ana", "ana@calimport.cl", "Bodeguera", true)));

        assertEquals(Rol.Despachador, ana.getRol());
        assertEquals(List.of("Ana"), cambios.altas());
    }

    @Test
    void sinCorreoOConElCorreoDeOtroNoSeDaDeAlta() {
        // El correo es único en la tabla: guardar el repetido rompería toda la sincronización.
        Repartidor juan = repartidor(7, "Juan Perez", "juan@calimport.cl", true);
        when(repartidorRepository.findAll()).thenReturn(List.of(juan));

        CambiosRepartidores cambios = service.sincronizarConSap(List.of(
                empleado(7, "Juan Perez", "juan@calimport.cl", "Bodeguero", true),
                empleado(31, "Sin Correo", "", "Bodeguero", true),
                empleado(32, "Repetido", "JUAN@calimport.cl", "Bodeguero", true)));

        verify(repartidorRepository, never()).save(any());
        assertTrue(cambios.altas().isEmpty());
        assertEquals(List.of("Sin Correo", "Repetido"), cambios.omitidos());
    }

    @Test
    void unaRespuestaVaciaDeSapNoDaDeBajaANadie() {
        // Es más probable un problema de SAP que una empresa sin empleados: no se vacía la lista.
        Repartidor juan = repartidor(7, "Juan Perez", "juan@calimport.cl", true);

        CambiosRepartidores cambios = service.sincronizarConSap(List.of());

        assertTrue(juan.isActivo());
        verify(repartidorRepository, never()).save(any());
        assertTrue(cambios.bajas().isEmpty());
    }

    @Test
    void recordarLaMismaVueltaNoEscribeNada() {
        Repartidor existente = repartidor(7, "Juan Perez", "juan@calimport.cl", true);
        existente.setVuelveABodega(true);
        when(repartidorRepository.findById(7)).thenReturn(Optional.of(existente));

        service.recordarVuelta(7, true);

        verify(repartidorRepository, never()).save(any());
    }
}
