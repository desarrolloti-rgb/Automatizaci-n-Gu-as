package com.calimport.guias.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.Repartidor;
import com.calimport.guias.model.Rol;
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

    /** Los que pueden recibir guías: activos y con rol Despachador (el jefe de bodega no reparte). */
    @Transactional(readOnly = true)
    public List<Repartidor> listarActivos() {
        return repartidorRepository.findByActivoTrueAndRol(Rol.Despachador);
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
    public Repartidor upsertDesdeSap(int employeeId, String nombre, String email, boolean activo, Rol rol) {
        Repartidor repartidor = repartidorRepository.findById(employeeId).orElseGet(Repartidor::new);
        repartidor.setEmployeeId(employeeId);
        repartidor.setNombre(nombre);
        repartidor.setEmail(email);
        repartidor.setActivo(activo);
        repartidor.setRol(rol);
        return repartidorRepository.save(repartidor);
    }

    /**
     * Deja como habitual lo que bodega marcó al generar su ruta. Uno de los repartidores vuelve
     * a bodega al terminar y el otro no: así el formulario lo trae marcado la próxima vez.
     */
    @Transactional
    public void recordarVuelta(int employeeId, boolean vuelveABodega) {
        Repartidor repartidor = obtenerPorId(employeeId);
        if (repartidor.isVuelveABodega() != vuelveABodega) {
            repartidor.setVuelveABodega(vuelveABodega);
            repartidorRepository.save(repartidor);
        }
    }
}
