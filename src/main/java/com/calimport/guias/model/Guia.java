package com.calimport.guias.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
public class Guia {

    // --- Identidad de la guía ---

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- Datos de la guía que vienen de SAP ---

    @Column(nullable = false)
    private int docEntry;

    @NotNull
    @Column(nullable = false)
    private Long folio;

    @NotBlank
    @Column(nullable = false)
    private String cliente;

    @NotBlank
    @Column(nullable = false)
    private String direccion;

    // --- Datos de la guía que se van generando en la app ---

    @NotNull
    @Column(nullable = false)
    private Integer repartidorId;

    @Column(nullable = false)
    private boolean recibidaPorRepartidor;

    @NotNull
    @Column(nullable = false)
    private Instant fechaRecepcionRepartidor;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoGuia estado = EstadoGuia.PENDIENTE;

    @NotNull
    @Column(nullable = false)
    private Instant fechaEntrega;

   /// --- Evidencia fotográfica ---

    @NotBlank
    @Column(nullable = false)
    private String urlFoto;

    /**
     * SHA-256 del archivo. Se queda en Postgres, no viaja a SAP. Es lo que permite
     * demostrar que la evidencia no se alteró después de capturada, y es imposible
     * de reconstruir si no se guarda en el momento.
     */
    @NotBlank
    @Column(nullable = false)
    private String hashFoto;

    // --- Sincronización con SAP ---

    @Column(nullable = false)
    private boolean sincronizada;

    // --- constructores ---

    protected Guia() {
    }

    public Guia(int docEntry, Long folio, String cliente, String direccion) {
        this.docEntry = docEntry;
        this.folio = folio;
        this.cliente = cliente;
        this.direccion = direccion;
    }


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getDocEntry() {
        return docEntry;
    }

    public void setDocEntry(int docEntry) {
        this.docEntry = docEntry;
    }

    public Long getFolio() {
        return folio;
    }

    public void setFolio(Long folio) {
        this.folio = folio;
    }

    public String getCliente() {
        return cliente;
    }

    public void setCliente(String cliente) {
        this.cliente = cliente;
    }

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(String direccion) {
        this.direccion = direccion;
    }

    public Integer getRepartidorId() {
        return repartidorId;
    }

    public void setRepartidorId(Integer repartidorId) {
        this.repartidorId = repartidorId;
    }

    public boolean isRecibidaPorRepartidor() {
        return recibidaPorRepartidor;
    }

    public void setRecibidaPorRepartidor(boolean recibidaPorRepartidor) {
        this.recibidaPorRepartidor = recibidaPorRepartidor;
    }

    public Instant getFechaRecepcionRepartidor() {
        return fechaRecepcionRepartidor;
    }

    public void setFechaRecepcionRepartidor(Instant fechaRecepcionRepartidor) {
        this.fechaRecepcionRepartidor = fechaRecepcionRepartidor;
    }

    public EstadoGuia getEstado() {
        return estado;
    }

    public void setEstado(EstadoGuia estado) {
        this.estado = estado;
    }

    public Instant getFechaEntrega() {
        return fechaEntrega;
    }

    public void setFechaEntrega(Instant fechaEntrega) {
        this.fechaEntrega = fechaEntrega;
    }

    public String getUrlFoto() {
        return urlFoto;
    }

    public void setUrlFoto(String urlFoto) {
        this.urlFoto = urlFoto;
    }

    public String getHashFoto() {
        return hashFoto;
    }

    public void setHashFoto(String hashFoto) {
        this.hashFoto = hashFoto;
    }

    public boolean isSincronizada() {
        return sincronizada;
    }

    public void setSincronizada(boolean sincronizada) {
        this.sincronizada = sincronizada;
    }
}
