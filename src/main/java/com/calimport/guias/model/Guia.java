package com.calimport.guias.model;

import java.time.Instant;
import java.time.LocalTime;

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

    /** Comments de SAP. Suele traer el horario de recepción escrito a mano. */
    private String comentario;

    // --- Datos para armar la ruta ---

    private Double latitud;

    private Double longitud;

    @Column(nullable = false)
    private boolean ubicacionAproximada;

    private LocalTime ventanaDesde;

    private LocalTime ventanaHasta;

    private String notaEntrega;

    /** Null mientras el comentario no se haya interpretado y el bodeguero no haya definido nada. */
    @Enumerated(EnumType.STRING)
    private OrigenHorario origenHorario;

    // --- Datos de la guía que se van generando en la app ---

    private Integer repartidorId;

    @Column(nullable = false)
    private boolean recibidaPorRepartidor;

    private Instant fechaRecepcionRepartidor;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoGuia estado = EstadoGuia.PENDIENTE;

    private Instant fechaEntrega;

   /// --- Evidencia fotográfica ---

    private String urlFoto;

    /**
     * SHA-256 del archivo. Se queda en Postgres, no viaja a SAP. Es lo que permite
     * demostrar que la evidencia no se alteró después de capturada, y es imposible
     * de reconstruir si no se guarda en el momento.
     */
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

    public String getComentario() {
        return comentario;
    }

    public void setComentario(String comentario) {
        this.comentario = comentario;
    }

    public Double getLatitud() {
        return latitud;
    }

    public void setLatitud(Double latitud) {
        this.latitud = latitud;
    }

    public Double getLongitud() {
        return longitud;
    }

    public void setLongitud(Double longitud) {
        this.longitud = longitud;
    }

    public boolean isUbicacionAproximada() {
        return ubicacionAproximada;
    }

    public void setUbicacionAproximada(boolean ubicacionAproximada) {
        this.ubicacionAproximada = ubicacionAproximada;
    }

    public boolean tieneUbicacion() {
        return latitud != null && longitud != null;
    }

    public LocalTime getVentanaDesde() {
        return ventanaDesde;
    }

    public void setVentanaDesde(LocalTime ventanaDesde) {
        this.ventanaDesde = ventanaDesde;
    }

    public LocalTime getVentanaHasta() {
        return ventanaHasta;
    }

    public void setVentanaHasta(LocalTime ventanaHasta) {
        this.ventanaHasta = ventanaHasta;
    }

    public String getNotaEntrega() {
        return notaEntrega;
    }

    public void setNotaEntrega(String notaEntrega) {
        this.notaEntrega = notaEntrega;
    }

    public OrigenHorario getOrigenHorario() {
        return origenHorario;
    }

    public void setOrigenHorario(OrigenHorario origenHorario) {
        this.origenHorario = origenHorario;
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
