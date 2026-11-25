package com.calimport.guias.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;

/**
 * Recorrido de un repartidor en un día: las guías en el orden en que conviene visitarlas.
 *
 * <p>Es la unidad que se mide (se miden rutas, no choferes): guarda su plan para compararlo
 * después contra lo real. Por eso regenerarla la <b>replanifica</b> ({@link #planificar}) en
 * vez de borrarla y crear otra: lo que ya se haya medido de ella no se pierde.
 */
@Entity
public class Ruta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer repartidorId;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(nullable = false)
    private LocalTime horaSalida;

    @Column(nullable = false)
    private Instant generadaEn;

    /**
     * Si el plan cuenta el tramo del último cliente a la bodega. Uno de los repartidores
     * vuelve y el otro no: sin este dato se compararía una ruta con vuelta contra una sin.
     * El nombre va explícito: la convención de Spring no separa "AB" y buscaría "vuelveabodega".
     */
    @Column(name = "vuelve_a_bodega", nullable = false)
    private boolean vuelveABodega;

    /** Km del recorrido completo según el optimizador, con la vuelta si la hay. Null en rutas viejas. */
    private Double kmPlanificados;

    /** Cuándo termina según el plan: al salir del último cliente, o al llegar a bodega si vuelve. */
    private Instant terminoEstimado;

    /**
     * Lo que marcaba el odómetro del auto al salir y al terminar, en km: el kilometraje real
     * contra el que se compara {@link #kmPlanificados}. Lo anota el repartidor en la app, y
     * cada lectura guarda cuándo se anotó, que es su respaldo. {@link #planificar} no lo toca:
     * regenerar la ruta con el repartidor ya en la calle no borra lo anotado.
     */
    private Integer odometroSalida;
    private Instant odometroSalidaEn;
    private Integer odometroTermino;
    private Instant odometroTerminoEn;

    @OneToMany(mappedBy = "ruta", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orden")
    private List<RutaParada> paradas = new ArrayList<>();

    protected Ruta() {
    }

    public Ruta(Integer repartidorId, LocalDate fecha, LocalTime horaSalida) {
        this.repartidorId = repartidorId;
        this.fecha = fecha;
        this.horaSalida = horaSalida;
        this.generadaEn = Instant.now();
    }

    /**
     * Deja el plan nuevo sobre esta misma ruta. Las paradas no se tocan acá: las borra antes
     * {@code RutaRepository.borrarParadasPrevias} y se vuelven a agregar en el orden nuevo.
     */
    public void planificar(LocalTime horaSalida, boolean vuelveABodega, Double kmPlanificados, Instant terminoEstimado) {
        this.horaSalida = horaSalida;
        this.vuelveABodega = vuelveABodega;
        this.kmPlanificados = kmPlanificados;
        this.terminoEstimado = terminoEstimado;
        this.generadaEn = Instant.now();
    }

    public void agregarParada(Guia guia, int orden, Instant llegadaEstimada) {
        paradas.add(new RutaParada(this, guia, orden, llegadaEstimada));
    }

    /** Corregir es volver a anotarla: queda la lectura nueva con la hora nueva. */
    public void anotarOdometroSalida(int km, Instant anotadoEn) {
        this.odometroSalida = km;
        this.odometroSalidaEn = anotadoEn;
    }

    public void anotarOdometroTermino(int km, Instant anotadoEn) {
        this.odometroTermino = km;
        this.odometroTerminoEn = anotadoEn;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getRepartidorId() {
        return repartidorId;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public LocalTime getHoraSalida() {
        return horaSalida;
    }

    public Instant getGeneradaEn() {
        return generadaEn;
    }

    public boolean isVuelveABodega() {
        return vuelveABodega;
    }

    public Double getKmPlanificados() {
        return kmPlanificados;
    }

    public Instant getTerminoEstimado() {
        return terminoEstimado;
    }

    public Integer getOdometroSalida() {
        return odometroSalida;
    }

    public Instant getOdometroSalidaEn() {
        return odometroSalidaEn;
    }

    public Integer getOdometroTermino() {
        return odometroTermino;
    }

    public Instant getOdometroTerminoEn() {
        return odometroTerminoEn;
    }

    public List<RutaParada> getParadas() {
        return paradas;
    }
}
