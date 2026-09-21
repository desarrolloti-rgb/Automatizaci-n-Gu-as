package com.calimport.guias.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class RutaParada {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ruta_id")
    private Ruta ruta;

    @ManyToOne(optional = false)
    @JoinColumn(name = "guia_id")
    private Guia guia;

    /** Posición en el recorrido, desde 1. */
    @Column(nullable = false)
    private int orden;

    /** Hora de llegada que calculó el optimizador, con tráfico. */
    @Column(nullable = false)
    private Instant llegadaEstimada;

    protected RutaParada() {
    }

    RutaParada(Ruta ruta, Guia guia, int orden, Instant llegadaEstimada) {
        this.ruta = ruta;
        this.guia = guia;
        this.orden = orden;
        this.llegadaEstimada = llegadaEstimada;
    }

    public Long getId() {
        return id;
    }

    public Ruta getRuta() {
        return ruta;
    }

    public Guia getGuia() {
        return guia;
    }

    public int getOrden() {
        return orden;
    }

    public Instant getLlegadaEstimada() {
        return llegadaEstimada;
    }
}
