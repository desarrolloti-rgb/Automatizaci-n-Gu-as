package com.calimport.guias.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Ruta;

@Repository
public interface RutaRepository extends JpaRepository<Ruta, Long> {

    Optional<Ruta> findByRepartidorIdAndFecha(Integer repartidorId, LocalDate fecha);

    /** El historial: las más nuevas primero. No toca las paradas (se cuentan aparte). */
    List<Ruta> findByFechaBetweenOrderByFechaDescRepartidorIdAsc(LocalDate desde, LocalDate hasta);

    List<Ruta> findByRepartidorIdAndFechaBetweenOrderByFechaDesc(Integer repartidorId, LocalDate desde,
                                                                  LocalDate hasta);

    /** Cuántas paradas tiene cada ruta, por resultado (null = sin resolver). */
    interface ConteoParadas {
        Long getRutaId();

        EstadoGuia getResultado();

        long getCantidad();
    }

    /**
     * Las paradas de estas rutas contadas por resultado, en una consulta: cargar cada ruta con
     * sus paradas traería también cada guía.
     */
    @Query("select p.ruta.id as rutaId, p.resultado as resultado, count(p) as cantidad"
            + " from RutaParada p where p.ruta.id in :rutaIds group by p.ruta.id, p.resultado")
    List<ConteoParadas> contarParadas(@Param("rutaIds") Collection<Long> rutaIds);

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
    /**
     * Anota cómo terminó la guía en su parada más reciente sin resolver: la de la ruta en que
     * salió esta vez. Las de rutas anteriores ya tienen su propio resultado. Es un UPDATE de dos
     * columnas, como {@code GuiaRepository.guardarCorreo}: no reescribe la parada entera.
     *
     * @return cuántas paradas marcó: 0 si la guía se entregó sin estar en una ruta
     */
    @Modifying(flushAutomatically = true)
    @Query("update RutaParada p set p.resultado = :resultado, p.resueltaEn = :cuando where p.id ="
            + " (select max(p2.id) from RutaParada p2 where p2.guia.id = :guiaId and p2.resultado is null)")
    int resolverParada(@Param("guiaId") Long guiaId, @Param("resultado") EstadoGuia resultado,
                       @Param("cuando") Instant cuando);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RutaParada p where p.guia.id = :guiaId and p.resultado is null"
            + " and p.ruta.id in (select r.id from Ruta r where r.fecha >= :desde)")
    void quitarGuiaDeRutas(@Param("guiaId") Long guiaId, @Param("desde") LocalDate desde);
}
