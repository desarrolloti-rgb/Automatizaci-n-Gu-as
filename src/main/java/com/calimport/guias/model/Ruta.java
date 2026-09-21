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

/** Recorrido de un repartidor en un día: las guías en el orden en que conviene visitarlas. */
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

    public void agregarParada(Guia guia, int orden, Instant llegadaEstimada) {
        paradas.add(new RutaParada(this, guia, orden, llegadaEstimada));
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

    public List<RutaParada> getParadas() {
        return paradas;
    }
}
