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

    /**
     * Antes de replanificar: borra las paradas de la ruta del día del repartidor y las de estas
     * guías en cualquier otra ruta (una guía va en una sola). La ruta misma no se borra: se
     * replanifica, porque es lo que se mide.
     *
     * <p>Es una consulta y no {@code paradas.clear()}: Hibernate ejecuta los INSERT antes que
     * los DELETE al hacer flush, y las paradas nuevas chocarían con la restricción UNIQUE de las
     * viejas. Una consulta @Modifying corre en el momento, y limpia el contexto de persistencia:
     * la ruta hay que buscarla después.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RutaParada p where p.guia.id in :guiaIds or p.ruta.id in "
            + "(select r.id from Ruta r where r.repartidorId = :repartidorId and r.fecha = :fecha)")
    void borrarParadasPrevias(@Param("guiaIds") Collection<Long> guiaIds,
                              @Param("repartidorId") Integer repartidorId,
                              @Param("fecha") LocalDate fecha);

    /**
     * Saca la guía de la ruta donde esté. Bodega la reasignó o la reabrió: si quedara, el
     * repartidor anterior la seguiría viendo en su recorrido sin poder tocarla (403). El resto
     * de la ruta queda igual; el orden salta un número, que es preferible a reordenar sin
     * volver a optimizar.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RutaParada p where p.guia.id = :guiaId")
    void quitarGuiaDeRutas(@Param("guiaId") Long guiaId);
}
