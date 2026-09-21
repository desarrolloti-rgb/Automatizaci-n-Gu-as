package com.calimport.guias.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.calimport.guias.model.Ruta;

@Repository
public interface RutaRepository extends JpaRepository<Ruta, Long> {

    Optional<Ruta> findByRepartidorIdAndFecha(Integer repartidorId, LocalDate fecha);

    /*
     * Los dos deletes son consultas y no repository.delete(): Hibernate ejecuta los INSERT
     * antes que los DELETE al hacer flush, y la ruta nueva chocaría con las restricciones
     * UNIQUE de la vieja. Una consulta @Modifying corre en el momento.
     */

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RutaParada p where p.guia.id in :guiaIds or p.ruta.id in "
            + "(select r.id from Ruta r where r.repartidorId = :repartidorId and r.fecha = :fecha)")
    void borrarParadasPrevias(@Param("guiaIds") Collection<Long> guiaIds,
                              @Param("repartidorId") Integer repartidorId,
                              @Param("fecha") LocalDate fecha);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Ruta r where r.repartidorId = :repartidorId and r.fecha = :fecha")
    void borrarRuta(@Param("repartidorId") Integer repartidorId, @Param("fecha") LocalDate fecha);
}
