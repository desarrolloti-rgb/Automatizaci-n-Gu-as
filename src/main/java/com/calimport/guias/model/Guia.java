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

    /**
     * Lo que el repartidor tiene que leer: el horario, el contacto y el teléfono del pie
     * del documento, más los {@code Comments} de SAP, todo en una línea.
     */
    @Column(length = 2000)
    private String comentario;

    /**
     * El pie del documento en SAP ({@code ClosingRemarks}), crudo y sin interpretar.
     *
     * <p>Es la evidencia de dónde salieron la dirección y el horario. Bodega puede
     * corregirlos, pero esto sigue mostrando qué decía el documento: sin él, una dirección
     * corregida no se puede contrastar con nada.
     */
    @Column(length = 2000)
    private String footer;

    /** La dirección que se leyó del pie, tal cual. No se corrige: para eso está {@link #direccion}. */
    private String direccionFooter;

    /** El horario que se leyó del pie, tal cual lo escribió el vendedor. */
    private String horarioFooter;

    /**
     * De dónde salió {@link #direccion}. Null en las guías importadas antes de que
     * existiera el campo.
     */
    @Enumerated(EnumType.STRING)
    private OrigenDireccion origenDireccion;

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

    /**
     * Cuándo la evidencia llegó a SAP: el PATCH que escribe {@code U_UrlFoto}.
     *
     * <p>No es lo mismo que {@link #fechaEntrega}. Esa es cuándo el repartidor cerró la
     * guía en su celular, que puede ser en la calle y sin señal; ésta es cuándo el dato
     * salió de esta app y quedó visible para el resto de la empresa. Entre las dos puede
     * haber minutos o, si SAP estaba caído, bastante más.
     */
    private Instant fechaFotoEnSap;

   /// --- Evidencia fotográfica ---

    private String urlFoto;

    /**
     * SHA-256 del archivo. Se queda en Postgres, no viaja a SAP. Es lo que permite
     * demostrar que la evidencia no se alteró después de capturada, y es imposible
     * de reconstruir si no se guarda en el momento.
     */
    private String hashFoto;

    /**
     * Por qué el cliente rechazó la guía, en palabras del repartidor. Solo lo tienen las
     * RECHAZADA: es su evidencia, el equivalente a la foto de una entrega, y lo que le
     * permite a bodega saber si hay que reprogramar, corregir el pedido o cobrar el flete.
     */
    private String motivoRechazo;

    // --- Sincronización con SAP ---

    /**
     * Si el estado logístico de esta guía ya está reflejado en SAP, que es la fuente de
     * verdad del ciclo.
     *
     * <p>Nace en <b>true</b>: una guía recién importada no tiene nada que contarle a SAP,
     * que ya la creó con {@code U_EstadoLog = 'P'}. Pasa a false recién cuando la app la
     * cambia (retiro, entrega, rechazo o reapertura) y vuelve a true cuando ese cambio
     * llega. Lo que queda en false es, literalmente, lo que SAP todavía no sabe.
     */
    @Column(nullable = false)
    private boolean sincronizada = true;

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

    public String getFooter() {
        return footer;
    }

    public void setFooter(String footer) {
        this.footer = footer;
    }

    public String getDireccionFooter() {
        return direccionFooter;
    }

    public void setDireccionFooter(String direccionFooter) {
        this.direccionFooter = direccionFooter;
    }

    public String getHorarioFooter() {
        return horarioFooter;
    }

    public void setHorarioFooter(String horarioFooter) {
        this.horarioFooter = horarioFooter;
    }

    public OrigenDireccion getOrigenDireccion() {
        return origenDireccion;
    }

    public void setOrigenDireccion(OrigenDireccion origenDireccion) {
        this.origenDireccion = origenDireccion;
    }

    public Instant getFechaFotoEnSap() {
        return fechaFotoEnSap;
    }

    public void setFechaFotoEnSap(Instant fechaFotoEnSap) {
        this.fechaFotoEnSap = fechaFotoEnSap;
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

    public String getMotivoRechazo() {
        return motivoRechazo;
    }

    public void setMotivoRechazo(String motivoRechazo) {
        this.motivoRechazo = motivoRechazo;
    }

    public boolean isSincronizada() {
        return sincronizada;
    }

    public void setSincronizada(boolean sincronizada) {
        this.sincronizada = sincronizada;
    }
}
