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
     * Antes de replanificar: borra las paradas sin resolver de la ruta del día del repartidor, y
     * las de estas guías en rutas de ese día en adelante (de otro repartidor, por ejemplo). La
     * ruta misma no se borra: se replanifica, porque es lo que se mide.
     *
     * <p><b>El pasado no se toca</b> (V15): una guía que ayer no se entregó sigue en la ruta de
     * ayer, y una parada ya resuelta sigue en la suya aunque se regenere. Es el historial de
     * rutas.
     *
     * <p>Es una consulta y no {@code paradas.clear()}: Hibernate ejecuta los INSERT antes que
     * los DELETE al hacer flush. Una consulta @Modifying corre en el momento, y limpia el
     * contexto de persistencia: la ruta hay que buscarla después.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RutaParada p where p.resultado is null and ("
            + "p.ruta.id in (select r.id from Ruta r where r.repartidorId = :repartidorId and r.fecha = :fecha)"
            + " or (p.guia.id in :guiaIds and p.ruta.id in (select r.id from Ruta r where r.fecha >= :fecha)))")
    void borrarParadasPrevias(@Param("guiaIds") Collection<Long> guiaIds,
                              @Param("repartidorId") Integer repartidorId,
                              @Param("fecha") LocalDate fecha);

    /**
     * Saca la guía de las rutas de {@code desde} en adelante. Bodega la reasignó, la marcó para
     * un transportista o la reabrió: si quedara, el repartidor anterior la seguiría viendo en su
     * recorrido sin poder tocarla (403). El resto de la ruta queda igual; el orden salta un
     * número, que es preferible a reordenar sin volver a optimizar.
     *
     * <p>Las rutas de días anteriores y las paradas ya resueltas no se tocan: son el historial
     * (V15). Una rechazada que se reabre sigue en su ruta, como rechazada.
     *
     * @param desde hoy, en la zona de las rutas
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RutaParada p where p.guia.id = :guiaId and p.resultado is null"
            + " and p.ruta.id in (select r.id from Ruta r where r.fecha >= :desde)")
    void quitarGuiaDeRutas(@Param("guiaId") Long guiaId, @Param("desde") LocalDate desde);
}
