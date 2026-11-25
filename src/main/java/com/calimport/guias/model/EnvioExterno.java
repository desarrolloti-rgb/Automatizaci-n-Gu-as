package com.calimport.guias.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Lo que el lector del portal de un transportista externo (SAMEX) encontró al buscar una guía por
 * su folio: el envío que le corresponde, en qué estado está y, si ya se entregó, cuándo y con qué
 * evidencia.
 *
 * <p>Una fila por guía, porque un envío es una guía y una guía es un envío. Se crea en la primera
 * búsqueda y se actualiza en cada una, también mientras el transportista no tiene el envío
 * ({@link ResultadoEnvio#NO_ENCONTRADO}): así se sabe desde cuándo se la busca sin éxito. Lo que se
 * guarda acá es lo que dice el transportista; la guía en sí la cambia solo {@code GuiaService}.
 */
@Entity
public class EnvioExterno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long guiaId;

    @Column(nullable = false, length = 50)
    private String transportista;

    /** El folio que se buscó, copiado de la guía: es lo que se comparó con las referencias. */
    @Column(nullable = false)
    private Long folio;

    /** El número de envío del transportista. Null mientras no haya uno confirmado. */
    @Column(length = 30)
    private String numero;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultadoEnvio resultado;

    /** Por qué no quedó asociado: es lo que ve bodega. Null cuando está asociado. */
    @Column(length = 500)
    private String motivo;

    /** Lo que dice el portal, tal cual ("Entregada movilidad 23/11/2026"). */
    @Column(length = 200)
    private String estadoPortal;

    /** Las referencias del envío en el portal, separadas por coma. */
    @Column(length = 500)
    private String referencias;

    /** Cuándo lo entregó el transportista, según su portal. Null mientras no lo entregue. */
    private Instant fechaEntrega;

    @Column(length = 500)
    private String evidenciaUrl;

    @Column(length = 64)
    private String evidenciaHash;

    /**
     * Bodega fijó el número a mano porque el folio no quedó bien escrito en el portal. El lector
     * busca por ese número y no exige el folio entre las referencias: lo garantizó una persona.
     *
     * <p>El nombre de la columna va explícito, como {@code vuelve_a_bodega}: la convención de Spring
     * no separa la "A" suelta y buscaría {@code fijadoamano}.
     */
    @Column(name = "fijado_a_mano", nullable = false)
    private boolean fijadoAMano;

    @Column(nullable = false)
    private Instant primeraBusqueda;

    @Column(nullable = false)
    private Instant ultimaBusqueda;

    protected EnvioExterno() {
    }

    /** Un seguimiento nuevo: la primera búsqueda de la guía, todavía sin envío. */
    public EnvioExterno(Long guiaId, String transportista, Long folio, Instant ahora) {
        this.guiaId = guiaId;
        this.transportista = transportista;
        this.folio = folio;
        this.resultado = ResultadoEnvio.NO_ENCONTRADO;
        this.primeraBusqueda = ahora;
        this.ultimaBusqueda = ahora;
    }

    public Long getId() {
        return id;
    }

    public Long getGuiaId() {
        return guiaId;
    }

    public String getTransportista() {
        return transportista;
    }

    public Long getFolio() {
        return folio;
    }

    public String getNumero() {
        return numero;
    }

    public void setNumero(String numero) {
        this.numero = numero;
    }

    public ResultadoEnvio getResultado() {
        return resultado;
    }

    public void setResultado(ResultadoEnvio resultado) {
        this.resultado = resultado;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public String getEstadoPortal() {
        return estadoPortal;
    }

    public void setEstadoPortal(String estadoPortal) {
        this.estadoPortal = estadoPortal;
    }

    public String getReferencias() {
        return referencias;
    }

    public void setReferencias(String referencias) {
        this.referencias = referencias;
    }

    public Instant getFechaEntrega() {
        return fechaEntrega;
    }

    public void setFechaEntrega(Instant fechaEntrega) {
        this.fechaEntrega = fechaEntrega;
    }

    public String getEvidenciaUrl() {
        return evidenciaUrl;
    }

    public void setEvidenciaUrl(String evidenciaUrl) {
        this.evidenciaUrl = evidenciaUrl;
    }

    public String getEvidenciaHash() {
        return evidenciaHash;
    }

    public void setEvidenciaHash(String evidenciaHash) {
        this.evidenciaHash = evidenciaHash;
    }

    public boolean isFijadoAMano() {
        return fijadoAMano;
    }

    public void setFijadoAMano(boolean fijadoAMano) {
        this.fijadoAMano = fijadoAMano;
    }

    public Instant getPrimeraBusqueda() {
        return primeraBusqueda;
    }

    public Instant getUltimaBusqueda() {
        return ultimaBusqueda;
    }

    public void setUltimaBusqueda(Instant ultimaBusqueda) {
        this.ultimaBusqueda = ultimaBusqueda;
    }
}
