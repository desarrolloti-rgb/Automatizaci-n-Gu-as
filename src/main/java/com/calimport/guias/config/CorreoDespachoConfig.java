package com.calimport.guias.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * El correo que le avisa al cliente que su despacho se entregó
 * ({@link com.calimport.guias.correo.CorreoDespachoService}). El servidor SMTP va aparte, en
 * {@code spring.mail.*}: Resend, igual que el Dashboard.
 */
@Configuration
@ConfigurationProperties(prefix = "correo.despacho")
public class CorreoDespachoConfig {

    /** Apagado por defecto: en desarrollo y en los tests no sale ningún correo. */
    private boolean habilitado;

    /** Tiene que ser de un dominio verificado en Resend. */
    private String remitente = "noreply@calimport.cl";

    /** Copia oculta interna, separada por comas. Vacía = sin copia. */
    private String copia = "";

    /** Casilla a la que llega si el cliente responde. Vacía = el correo pide no responder. */
    private String responderA = "";

    /**
     * Para probar: todo va a esta casilla y no al cliente, sin copia, con "[PRUEBA]" en el
     * asunto y diciendo a quién habría ido. Obligatorio contra la base de TEST de SAP.
     */
    private String desvio = "";

    /** Intentos antes de darlo por fallido. Con la espera creciente, 12 son unas 3,5 horas. */
    private int maxIntentos = 12;

    /** Cada cuánto se revisan los pendientes. Solo se declara para documentarlo: lo lee el @Scheduled. */
    private long cicloMs = 60_000;

    public boolean isHabilitado() { return habilitado; }
    public void setHabilitado(boolean habilitado) { this.habilitado = habilitado; }

    public String getRemitente() { return remitente; }
    public void setRemitente(String remitente) { this.remitente = remitente; }

    public String getCopia() { return copia; }
    public void setCopia(String copia) { this.copia = copia; }

    public String getResponderA() { return responderA; }
    public void setResponderA(String responderA) { this.responderA = responderA; }

    public String getDesvio() { return desvio; }
    public void setDesvio(String desvio) { this.desvio = desvio; }

    public int getMaxIntentos() { return maxIntentos; }
    public void setMaxIntentos(int maxIntentos) { this.maxIntentos = maxIntentos; }

    public long getCicloMs() { return cicloMs; }
    public void setCicloMs(long cicloMs) { this.cicloMs = cicloMs; }
}
