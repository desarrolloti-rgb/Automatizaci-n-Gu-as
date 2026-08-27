package com.calimport.guias.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.Repartidor;
import com.calimport.guias.repository.RepartidorRepository;
import com.calimport.guias.utils.ApiException;

@Service
public class RepartidorService {

    private final RepartidorRepository repartidorRepository;

    public RepartidorService(RepartidorRepository repartidorRepository) {
        this.repartidorRepository = repartidorRepository;
    }

    @Transactional(readOnly = true)
    public List<Repartidor> listar() {
        return repartidorRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Repartidor> listarActivos() {
        return repartidorRepository.findByActivoTrue();
    }

    @Transactional(readOnly = true)
    public Repartidor obtenerPorId(int employeeId) {
        return repartidorRepository.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No existe un repartidor con id " + employeeId));
    }

    /**
     * Guarda o actualiza la copia local con lo último que vino de SAP. Se llama en cada
     * login exitoso: es la forma en que la copia local se mantiene al día, sin un job aparte.
     */
    @Transactional
    public Repartidor upsertDesdeSap(int employeeId, String nombre, String email, boolean activo) {
        Repartidor repartidor = repartidorRepository.findById(employeeId).orElseGet(Repartidor::new);
        repartidor.setEmployeeId(employeeId);
        repartidor.setNombre(nombre);
        repartidor.setEmail(email);
        repartidor.setActivo(activo);
        return repartidorRepository.save(repartidor);
    }
}
