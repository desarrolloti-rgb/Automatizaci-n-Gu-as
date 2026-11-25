package com.calimport.guias.correo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.calimport.guias.config.CorreoDespachoConfig;
import com.calimport.guias.config.SapConfig;
import com.calimport.guias.model.EstadoCorreo;
import com.calimport.guias.model.Guia;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.sap.SapClient;
import com.calimport.guias.service.AlmacenamientoFotos;
import com.calimport.guias.service.AlmacenamientoFotos.Foto;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import tools.jackson.databind.JsonNode;

/**
 * Le avisa al cliente por correo que su despacho se entregó, con la foto de la entrega
 * adjunta y copia oculta a una casilla interna.
 *
 * <p><b>La entrega no envía nada</b>: solo deja el correo PENDIENTE ({@link #programar}) y
 * este proceso lo manda en el minuto siguiente. Enviar ahí mismo haría esperar al repartidor,
 * en la calle y con mala señal, a que respondan SAP (el correo del cliente) y Resend; y si
 * alguno estuviera caído habría que elegir entre perder el aviso o rechazar una entrega que
 * ya ocurrió. Es el mismo criterio del envío a SAP: primero Postgres, lo externo después.
 *
 * <p>El correo del cliente es el {@code EmailAddress} de su ficha en SAP, el mismo con el que
 * la app de HES le pide la HES. Se consulta al enviar, así que corregirlo en SAP sirve para
 * los reintentos.
 *
 * <p><b>Enviar no es idempotente</b>, a diferencia del PATCH a SAP: reintentar de más le
 * llega dos veces al cliente. Por eso el estado se guarda apenas sale y sin tocar el resto de
 * la guía ({@link GuiaRepository#guardarCorreo}), y los reintentos tienen tope: la cuota de
 * Resend la comparten otros sistemas de Calimport.
 */
@Service
public class CorreoDespachoService {

    private static final Logger log = LoggerFactory.getLogger(CorreoDespachoService.class);

    private static final String NOMBRE_REMITENTE = "Calimport · Despachos";
    /** El logo de los correos de HES (recortado de la firma corporativa): la misma marca en los dos. */
    private static final String RUTA_LOGO = "mail/logo-calimport.png";
    private static final Duration ESPERA_MAXIMA = Duration.ofMinutes(30);
    private static final int LARGO_MOTIVO = 255;

    private final GuiaRepository guiaRepository;
    private final SapClient sapClient;
    private final AlmacenamientoFotos fotos;
    private final JavaMailSender mailSender;
    private final boolean habilitado;
    private final InternetAddress remitente;
    private final List<String> copia;
    private final InternetAddress responderA;
    private final String desvio;
    private final int maxIntentos;
    private final byte[] logo = cargarLogo();

    @Autowired
    public CorreoDespachoService(GuiaRepository guiaRepository, SapClient sapClient, AlmacenamientoFotos fotos,
                                 JavaMailSender mailSender, CorreoDespachoConfig config, SapConfig sapConfig,
                                 @Value("${spring.mail.password:}") String smtpPassword) {
        this.guiaRepository = guiaRepository;
        this.sapClient = sapClient;
        this.fotos = fotos;
        this.mailSender = mailSender;
        this.habilitado = config.isHabilitado();
        this.maxIntentos = Math.max(1, config.getMaxIntentos());
        this.copia = DireccionesCorreo.extraer(config.getCopia());
        this.desvio = vacioANull(config.getDesvio());
        String responder = vacioANull(config.getResponderA());

        if (habilitado) {
            // Fallar al arrancar y no en la primera entrega: es mejor enterarse al desplegar
            // que después de una semana de avisos sin salir.
            if (smtpPassword == null || smtpPassword.isBlank()) {
                throw new IllegalStateException("CORREO_DESPACHO=true requiere SMTP_PASSWORD (la API key de Resend). "
                        + "Configúrala o deja CORREO_DESPACHO=false.");
            }
            if (!DireccionesCorreo.esValida(config.getRemitente())) {
                throw new IllegalStateException("CORREO_DESPACHO_REMITENTE no es un correo válido: '"
                        + config.getRemitente() + "'");
            }
            // Un desvío mal escrito no se puede ignorar: sin él, la prueba le escribe a los
            // clientes de verdad.
            if (desvio != null && !DireccionesCorreo.esValida(desvio)) {
                throw new IllegalStateException("CORREO_DESPACHO_DESVIO no es un correo válido: '" + desvio + "'");
            }
            if (responder != null && !DireccionesCorreo.esValida(responder)) {
                throw new IllegalStateException("CORREO_DESPACHO_RESPONDER_A no es un correo válido: '"
                        + responder + "'");
            }
            // La base de TEST de SAP es una copia de producción: sus clientes tienen los
            // correos reales. La instancia de prueba copia el .env de producción, así que sin
            // este resguardo bastaría olvidar una línea para escribirles por guías de prueba.
            String base = sapConfig.getCompanyDb();
            if (desvio == null && base != null && base.toUpperCase(Locale.ROOT).contains("TEST")) {
                throw new IllegalStateException("Contra la base de pruebas de SAP (" + base + ") el correo de "
                        + "despacho exige CORREO_DESPACHO_DESVIO, o dejar CORREO_DESPACHO=false.");
            }
        }
        this.remitente = habilitado ? direccion(config.getRemitente(), NOMBRE_REMITENTE) : null;
        this.responderA = habilitado && responder != null ? direccion(responder, null) : null;
        if (habilitado) {
            log.info("Correo de despacho encendido{}", desvio != null ? " con desvío de prueba" : "");
        }
    }

    /**
     * Deja el correo por enviar. Lo llama la entrega, dentro de su transacción: el correo sale
     * después, desde {@link #enviarPendientes()}.
     *
     * <p>Apagado no marca nada, a propósito: si las entregas quedaran pendientes, encenderlo
     * después le escribiría de golpe a cada cliente por despachos de hace días.
     */
    public void programar(Guia guia) {
        if (!habilitado) {
            return;
        }
        guia.setCorreoEstado(EstadoCorreo.PENDIENTE);
        guia.setCorreoIntentos(0);
        guia.setCorreoFecha(null);
        guia.setCorreoDestino(null);
        guia.setCorreoMotivo(null);
    }

    @Scheduled(fixedDelayString = "${correo.despacho.ciclo-ms:60000}",
               initialDelayString = "${correo.despacho.ciclo-ms:60000}")
    public void enviarPendientes() {
        enviarPendientes(Instant.now());
    }

    void enviarPendientes(Instant ahora) {
        if (!habilitado) {
            return;
        }
        for (Guia guia : guiaRepository.findByCorreoEstado(EstadoCorreo.PENDIENTE)) {
            if (!toca(guia, ahora)) {
                continue;
            }
            try {
                enviar(guia, ahora);
            } catch (RuntimeException e) {
                // Solo llega acá si falla guardar el resultado: una guía no frena a las demás.
                log.error("No se pudo registrar el correo de despacho de la guia folio {}: {}",
                        guia.getFolio(), e.getMessage(), e);
            }
        }
    }

    /** Si ya pasó la espera desde el último intento. El primero no espera. */
    static boolean toca(Guia guia, Instant ahora) {
        if (guia.getCorreoIntentos() == 0 || guia.getCorreoFecha() == null) {
            return true;
        }
        return !ahora.isBefore(guia.getCorreoFecha().plus(espera(guia.getCorreoIntentos())));
    }

    /**
     * 1, 2, 4, 8 y 16 minutos, y desde ahí media hora. Con SAP o Resend caídos, insistir cada
     * minuto no lo arregla y solo llena el log; con un corte breve, el aviso igual sale pronto.
     */
    static Duration espera(int intentos) {
        long minutos = 1L << Math.min(Math.max(intentos, 1) - 1, 5);
        Duration espera = Duration.ofMinutes(minutos);
        return espera.compareTo(ESPERA_MAXIMA) > 0 ? ESPERA_MAXIMA : espera;
    }

    private void enviar(Guia guia, Instant ahora) {
        guia.setCorreoIntentos(guia.getCorreoIntentos() + 1);
        guia.setCorreoFecha(ahora);
        try {
            Destino destino = destinoPara(guia);
            if (destino.para().isEmpty()) {
                guia.setCorreoEstado(EstadoCorreo.SIN_CORREO);
                guia.setCorreoMotivo(recortar(destino.motivo()));
                log.info("Guia folio {}: no se envia el correo de despacho ({})", guia.getFolio(), destino.motivo());
            } else {
                MimeMessage mensaje = armar(guia, destino);
                try {
                    mailSender.send(mensaje);
                } catch (MailException e) {
                    throw new IllegalStateException("El servidor de correo no lo aceptó: " + describir(e), e);
                }
                guia.setCorreoEstado(EstadoCorreo.ENVIADO);
                guia.setCorreoDestino(recortar(String.join(", ", destino.para()), 500));
                guia.setCorreoMotivo(null);
                // Sin las casillas: el log no lleva datos de los clientes.
                log.info("Correo de despacho de la guia folio {} enviado", guia.getFolio());
            }
        } catch (RuntimeException e) {
            fallo(guia, describir(e));
        }
        guiaRepository.guardarCorreo(guia);
    }

    private void fallo(Guia guia, String motivo) {
        boolean agotado = guia.getCorreoIntentos() >= maxIntentos;
        guia.setCorreoEstado(agotado ? EstadoCorreo.FALLIDO : EstadoCorreo.PENDIENTE);
        guia.setCorreoMotivo(recortar(motivo));
        log.warn("Correo de despacho de la guia folio {} sin enviar (intento {} de {}{}): {}", guia.getFolio(),
                guia.getCorreoIntentos(), maxIntentos, agotado ? ", no se reintenta" : "", motivo);
    }

    /**
     * A quién va. Sin casillas, {@code motivo} dice por qué. Con desvío de prueba va siempre
     * al desvío, aunque SAP no conteste: en el PC no hay SAP y así se prueba el correo entero.
     */
    private record Destino(List<String> para, List<String> cco, String motivo, String enProduccion) {
    }

    private Destino destinoPara(Guia guia) {
        if (desvio != null) {
            // Como el modo prueba de HES: el aviso dice a quién se le habría enviado.
            String habria;
            try {
                CorreosCliente cliente = correosDelCliente(guia);
                habria = cliente.correos().isEmpty()
                        ? "(sin correo en SAP: " + cliente.motivo() + ")"
                        : String.join(", ", cliente.correos());
            } catch (RuntimeException e) {
                habria = "(no se pudo saber: " + describir(e) + ")";
            }
            return new Destino(List.of(desvio), List.of(), null, habria);
        }
        CorreosCliente cliente = correosDelCliente(guia);
        if (cliente.correos().isEmpty()) {
            return new Destino(List.of(), List.of(), cliente.motivo(), null);
        }
        List<String> cco = copia.stream().filter(c -> !cliente.correos().contains(c)).toList();
        return new Destino(cliente.correos(), cco, null, null);
    }

    private record CorreosCliente(List<String> correos, String motivo) {
    }

    /** Lanza si SAP no contesta: queda para reintento. Sin cliente o sin correo, no lanza. */
    private CorreosCliente correosDelCliente(Guia guia) {
        try {
            String cardCode = primerTexto(sapClient.fetchClienteDeGuia(guia.getDocEntry()), "CardCode");
            if (cardCode == null) {
                return new CorreosCliente(List.of(), "La guía no está en SAP");
            }
            List<String> correos = DireccionesCorreo.extraer(
                    primerTexto(sapClient.fetchCorreoDelCliente(cardCode), "EmailAddress"));
            return correos.isEmpty()
                    ? new CorreosCliente(List.of(), "El cliente " + cardCode + " no tiene correo en su ficha de SAP")
                    : new CorreosCliente(correos, null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("No se pudo consultar el cliente en SAP: " + describir(e), e);
        }
    }

    private static String primerTexto(JsonNode respuesta, String campo) {
        JsonNode filas = respuesta == null ? null : respuesta.get("value");
        if (filas == null || !filas.isArray() || filas.isEmpty()) {
            return null;
        }
        JsonNode valor = filas.get(0).get(campo);
        if (valor == null || valor.isNull()) {
            return null;
        }
        String texto = valor.asText("").trim();
        return texto.isEmpty() ? null : texto;
    }

    private MimeMessage armar(Guia guia, Destino destino) {
        Optional<Foto> foto = fotoDe(guia);
        MensajeDespacho.Contenido contenido = MensajeDespacho.de(guia, foto.isPresent(), responderA != null,
                destino.enProduccion(), logo != null);
        MimeMessage mensaje = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, true, StandardCharsets.UTF_8.name());
            helper.setFrom(remitente);
            helper.setTo(destino.para().toArray(String[]::new));
            if (!destino.cco().isEmpty()) {
                helper.setBcc(destino.cco().toArray(String[]::new));
            }
            if (responderA != null) {
                helper.setReplyTo(responderA);
            }
            helper.setSubject(contenido.asunto());
            helper.setText(contenido.texto(), contenido.html());
            // Incrustado y no por URL: Outlook y Gmail bloquean por defecto las imágenes remotas.
            // Va después de setText, como pide MimeMessageHelper.
            if (logo != null) {
                helper.addInline(MensajeDespacho.CID_LOGO, new ByteArrayResource(logo), "image/png");
            }
            // Como en HES, la foto va dos veces: incrustada, para verla en el cuerpo sin abrir
            // nada, y adjunta, para guardarla o reenviarla con su nombre.
            if (foto.isPresent()) {
                ByteArrayResource bytes = new ByteArrayResource(foto.get().contenido());
                String tipo = foto.get().tipo().toString();
                helper.addInline(MensajeDespacho.CID_FOTO, bytes, tipo);
                helper.addAttachment(nombreAdjunto(guia, foto.get()), bytes, tipo);
            }
        } catch (MessagingException e) {
            throw new MailPreparationException("No se pudo armar el correo de la guía " + guia.getFolio(), e);
        }
        return mensaje;
    }

    /**
     * La foto de la entrega. Si no está (no debería pasar: es la evidencia), el aviso sale
     * igual sin ella; si el almacén no contesta, se reintenta.
     */
    private Optional<Foto> fotoDe(Guia guia) {
        String nombre = AlmacenamientoFotos.nombreDe(guia.getUrlFoto());
        if (nombre == null) {
            return Optional.empty();
        }
        Optional<Foto> foto;
        try {
            foto = fotos.leer(nombre);
        } catch (RuntimeException e) {
            throw new IllegalStateException("No se pudo leer la foto de la entrega: " + describir(e), e);
        }
        if (foto.isEmpty()) {
            log.warn("La foto de la guia folio {} no esta en el almacen: el correo sale sin ella", guia.getFolio());
        }
        return foto;
    }

    private static String nombreAdjunto(Guia guia, Foto foto) {
        String subtipo = foto.tipo().getSubtype();
        String extension = "jpeg".equals(subtipo) ? "jpg" : subtipo;
        return "entrega-guia-" + guia.getFolio() + "." + extension;
    }

    /** Va dentro del jar. Si faltara, el correo sale igual con el nombre en texto. */
    private static byte[] cargarLogo() {
        try (InputStream in = new ClassPathResource(RUTA_LOGO).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            log.warn("No se encontró el logo del correo ({}): los avisos salen sin logo", RUTA_LOGO);
            return null;
        }
    }

    private static InternetAddress direccion(String email, String nombre) {
        try {
            return new InternetAddress(email.trim(), nombre, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String describir(Throwable e) {
        String mensaje = e.getMessage();
        return mensaje == null || mensaje.isBlank() ? e.getClass().getSimpleName() : mensaje.strip();
    }

    private static String recortar(String texto) {
        return recortar(texto, LARGO_MOTIVO);
    }

    private static String recortar(String texto, int largo) {
        if (texto == null) {
            return null;
        }
        String enUnaLinea = texto.replaceAll("\\s+", " ");
        return enUnaLinea.length() <= largo ? enUnaLinea : enUnaLinea.substring(0, largo - 1) + "…";
    }

    private static String vacioANull(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
