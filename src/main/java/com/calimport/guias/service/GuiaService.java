package com.calimport.guias.service;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.utils.ApiException;

@Service
public class GuiaService {

    private GuiaRepository guiaRepository;

    public GuiaService(GuiaRepository guiaRepository) {
        this.guiaRepository = guiaRepository;
    }

    /** Da de alta una guía a partir de los datos que vienen de SAP. */
    @Transactional
    public Guia crear(int docEntry, Long folio, String cliente, String direccion) {
        guiaRepository.findByDocEntry(docEntry).ifPresent(g -> {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe una guía para el docEntry " + docEntry);
        });
        return guiaRepository.save(new Guia(docEntry, folio, cliente, direccion));
    }

    //Permite leer las guías sin necesidad de transacción, ya que no se modifican los datos.

    @Transactional(readOnly = true)
    public List<Guia> listar() {
        return guiaRepository.findAll();
    }


    //Lista las guías que están en un estado específico (PENDIENTE, ENTREGADA, RECHAZADA).
    @Transactional(readOnly = true)
    public List<Guia> listarPorEstado(EstadoGuia estado) {
        return guiaRepository.findByEstado(estado);
    }

    //Lista las guías asignadas a un repartidor específico, identificado por su employeeId.

    @Transactional(readOnly = true)
    public List<Guia> listarPorRepartidor(Integer repartidorId) {
        return guiaRepository.findByRepartidorId(repartidorId);
    }

    //Permite obtener una guía por su id, lanzando una excepción si no existe.

    @Transactional(readOnly = true)
    public Guia obtenerPorId(Long id) {
        return guiaRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No existe una guía con id " + id));
    }

    // Bodega asigna qué repartidor lleva la guía. Solo antes de que empiece el reparto. */
    @Transactional
    public Guia asignarRepartidor(Long id, Integer repartidorId) {
        if (repartidorId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "repartidorId es obligatorio");
        }
        Guia guia = obtenerPorId(id);
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo se puede asignar repartidor a una guía PENDIENTE");
        }
        guia.setRepartidorId(repartidorId);
        return guiaRepository.save(guia);
    }

    // El repartidor confirma que retiró la guía para salir a reparto. */
    @Transactional
    public Guia marcarRecibidaPorRepartidor(Long id) {
        Guia guia = obtenerPorId(id);
        if (guia.getRepartidorId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "La guía todavía no tiene repartidor asignado");
        }
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo se puede recibir una guía PENDIENTE");
        }
        guia.setRecibidaPorRepartidor(true);
        guia.setFechaRecepcionRepartidor(Instant.now());
        return guiaRepository.save(guia);
    }

    // Cliente recibió todo y firmó: se guarda la evidencia fotográfica. */
    @Transactional
    public Guia entregar(Long id, String urlFoto, String hashFoto) {
        if (urlFoto == null || urlFoto.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "urlFoto es obligatoria para entregar la guía");
        }
        if (hashFoto == null || hashFoto.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "hashFoto es obligatorio para entregar la guía");
        }
        Guia guia = obtenerPorId(id);
        validarQuePuedaResolverse(guia);
        guia.setEstado(EstadoGuia.ENTREGADA);
        guia.setFechaEntrega(Instant.now());
        guia.setUrlFoto(urlFoto);
        guia.setHashFoto(hashFoto);
        return guiaRepository.save(guia);
    }

    // Cliente rechazó el despacho completo. 
    @Transactional
    public Guia rechazar(Long id) {
        Guia guia = obtenerPorId(id);
        validarQuePuedaResolverse(guia);
        guia.setEstado(EstadoGuia.RECHAZADA);
        guia.setFechaEntrega(Instant.now());
        return guiaRepository.save(guia);
    }

    // Marca que el resultado (entrega o rechazo) ya viajó de vuelta a SAP. */
    @Transactional
    public Guia marcarSincronizada(Long id) {
        Guia guia = obtenerPorId(id);
        guia.setSincronizada(true);
        return guiaRepository.save(guia);
    }

    private void validarQuePuedaResolverse(Guia guia) {
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "La guía ya fue resuelta (estado " + guia.getEstado() + ")");
        }
    }
}
