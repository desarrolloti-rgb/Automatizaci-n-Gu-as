package com.calimport.guias.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.EstadoCorreo;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;

@Repository
public interface GuiaRepository extends JpaRepository<Guia, Long> {

    Optional<Guia> findByDocEntry(int docEntry);

    List<Guia> findByEstado(EstadoGuia estado);

    List<Guia> findByRepartidorId(Integer repartidorId);

    List<Guia> findByRepartidorIdAndEstado(Integer repartidorId, EstadoGuia estado);

    /** Las que lleva un transportista externo y siguen en ese estado: las que su lector busca. */
    List<Guia> findByTransportistaAndEstado(String transportista, EstadoGuia estado);

    /** Las que cambiaron en la app y todavía no llegaron a SAP, para reintentar el envío. */
    List<Guia> findBySincronizadaFalse();

    List<Guia> findByCorreoEstado(EstadoCorreo correoEstado);

    /**
     * Guarda en qué quedó el correo de despacho, sin tocar el resto de la guía. El proceso de
     * correo trabaja con una copia leída antes de enviar (segundos, a veces más): un save de
     * la guía completa pisaría lo que haya cambiado mientras tanto, como el envío a SAP.
     */
    default void guardarCorreo(Guia guia) {
        actualizarCorreo(guia.getId(), guia.getCorreoEstado(), guia.getCorreoDestino(),
                guia.getCorreoIntentos(), guia.getCorreoFecha(), guia.getCorreoMotivo());
    }

    @Transactional
    @Modifying
    @Query("""
            update Guia g set g.correoEstado = :estado, g.correoDestino = :destino,
                g.correoIntentos = :intentos, g.correoFecha = :fecha, g.correoMotivo = :motivo
            where g.id = :id""")
    int actualizarCorreo(@Param("id") Long id, @Param("estado") EstadoCorreo estado,
                         @Param("destino") String destino, @Param("intentos") int intentos,
                         @Param("fecha") Instant fecha, @Param("motivo") String motivo);

}
