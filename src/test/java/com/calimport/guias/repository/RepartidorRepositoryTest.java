package com.calimport.guias.repository;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.Repartidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RepartidorRepositoryTest {

    @Autowired
    private RepartidorRepository repartidorRepository;

    private Repartidor repartidor(int employeeId, String nombre, String email, boolean activo) {
        Repartidor repartidor = new Repartidor();
        repartidor.setEmployeeId(employeeId);
        repartidor.setNombre(nombre);
        repartidor.setEmail(email);
        repartidor.setActivo(activo);
        return repartidor;
    }

    @Test
    void elEmployeeIdDeSapSeConservaComoIdYNoSeAutogenera() {
        // La identidad viene de SAP: si JPA generara el id, se duplicaría a la persona.
        repartidorRepository.saveAndFlush(repartidor(4321, "Juan Perez", "juan@calimport.cl", true));

        Optional<Repartidor> encontrado = repartidorRepository.findById(4321);

        assertTrue(encontrado.isPresent());
        assertEquals(4321, encontrado.get().getEmployeeId());
    }

    @Test
    void guardarDosVecesElMismoEmployeeIdActualizaLaFilaEnLugarDeDuplicarla() {
        repartidorRepository.saveAndFlush(repartidor(7, "Nombre Viejo", "juan@calimport.cl", true));
        repartidorRepository.saveAndFlush(repartidor(7, "Nombre Nuevo", "juan@calimport.cl", true));

        assertEquals(1, repartidorRepository.count());
        assertEquals("Nombre Nuevo", repartidorRepository.findById(7).orElseThrow().getNombre());
    }

    @Test
    void findByEmailEncuentraAlRepartidorQueInicioSesion() {
        repartidorRepository.saveAndFlush(repartidor(7, "Juan Perez", "juan@calimport.cl", true));

        Optional<Repartidor> encontrado = repartidorRepository.findByEmail("juan@calimport.cl");

        assertTrue(encontrado.isPresent());
        assertEquals(7, encontrado.get().getEmployeeId());
    }

    @Test
    void findByEmailDevuelveVacioSiEseCorreoNuncaEntro() {
        assertTrue(repartidorRepository.findByEmail("nadie@calimport.cl").isEmpty());
    }

    @Test
    void findByActivoTrueExcluyeALosDadosDeBajaEnSap() {
        repartidorRepository.save(repartidor(7, "Juan Perez", "juan@calimport.cl", true));
        repartidorRepository.saveAndFlush(repartidor(8, "Pedro Soto", "pedro@calimport.cl", false));

        List<Repartidor> activos = repartidorRepository.findByActivoTrue();

        assertEquals(1, activos.size());
        assertEquals(7, activos.get(0).getEmployeeId());
    }

    @Test
    void dosRepartidoresNoPuedenCompartirElMismoCorreo() {
        // El correo es la credencial de login: repetirlo haría ambiguo quién entra.
        repartidorRepository.saveAndFlush(repartidor(7, "Juan Perez", "juan@calimport.cl", true));

        Repartidor otroConMismoCorreo = repartidor(8, "Pedro Soto", "juan@calimport.cl", true);

        assertThrows(DataIntegrityViolationException.class,
                () -> repartidorRepository.saveAndFlush(otroConMismoCorreo));
    }
}
