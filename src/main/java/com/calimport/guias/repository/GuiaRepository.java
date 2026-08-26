package com.calimport.guias.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;

@Repository
public interface GuiaRepository extends JpaRepository<Guia, Long> {

    Optional<Guia> findByDocEntry(int docEntry);

    List<Guia> findByEstado(EstadoGuia estado);

    List<Guia> findByRepartidorId(Integer repartidorId);

}
