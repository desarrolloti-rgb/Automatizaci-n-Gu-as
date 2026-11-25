package com.calimport.guias.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.calimport.guias.model.EnvioExterno;

@Repository
public interface EnvioExternoRepository extends JpaRepository<EnvioExterno, Long> {

    Optional<EnvioExterno> findByGuiaId(Long guiaId);

    List<EnvioExterno> findByGuiaIdIn(Collection<Long> guiaIds);

    List<EnvioExterno> findByTransportista(String transportista);

    /** El seguimiento que ya tiene ese número de envío: un envío no puede quedar en dos guías. */
    Optional<EnvioExterno> findByTransportistaAndNumero(String transportista, String numero);
}
