package com.calimport.guias.correo;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import com.calimport.guias.config.CorreoDespachoConfig;
import com.calimport.guias.config.SapConfig;
import com.calimport.guias.model.EstadoCorreo;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.sap.SapClient;
import com.calimport.guias.service.AlmacenamientoFotos;
import com.calimport.guias.service.AlmacenamientoFotos.Foto;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorreoDespachoServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-19T15:00:00Z");
    private static final int DOC_ENTRY = 46263;
    private static final String CARD_CODE = "C76543210";
    private static final String NOMBRE_FOTO = "guia-10-0f8fad5b-d9cb-469f-a165-70867728950e.jpg";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    @Mock
    private GuiaRepository guiaRepository;
    @Mock
    private SapClient sapClient;
    @Mock
    private AlmacenamientoFotos fotos;
    @Mock
    private JavaMailSender mailSender;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // MimeMessageHelper necesita un mensaje real para armarlo; enviarlo es lo que se simula.
        lenient().when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    private static CorreoDespachoConfig encendido() {
        CorreoDespachoConfig config = new CorreoDespachoConfig();
        config.setHabilitado(true);
        config.setCopia("logistica@calimport.cl");
        return config;
    }

    private static SapConfig sap(String companyDb) {
        SapConfig sap = new SapConfig();
        sap.setCompanyDb(companyDb);
        return sap;
    }

    private CorreoDespachoService servicio(CorreoDespachoConfig config) {
        return new CorreoDespachoService(guiaRepository, sapClient, fotos, mailSender, config,
                sap("CALIMPORT_PRODUCTIVO"), "re_clave_de_prueba");
    }

    private CorreoDespachoService servicio() {
        return servicio(encendido());
    }

    private static Guia porEnviar() {
        Guia guia = new Guia(DOC_ENTRY, 187350L, "Constructora Andes SpA", "San Nicolás 630, San Miguel");
        guia.setId(10L);
        guia.setEstado(EstadoGuia.ENTREGADA);
        guia.setFechaEntrega(Instant.parse("2026-11-19T14:58:00Z"));
        guia.setUrlFoto("/api/fotos/" + NOMBRE_FOTO);
        guia.setCorreoEstado(EstadoCorreo.PENDIENTE);
        return guia;
    }

    private JsonNode json(String texto) {
        return mapper.readTree(texto);
    }

    private void sapDiceQueElClienteTiene(String emailAddress) {
        when(sapClient.fetchClienteDeGuia(DOC_ENTRY))
                .thenReturn(json("{\"value\":[{\"DocEntry\":46263,\"CardCode\":\"" + CARD_CODE + "\"}]}"));
        when(sapClient.fetchCorreoDelCliente(CARD_CODE)).thenReturn(json(emailAddress == null
                ? "{\"value\":[{\"CardCode\":\"" + CARD_CODE + "\",\"EmailAddress\":null}]}"
                : "{\"value\":[{\"CardCode\":\"" + CARD_CODE + "\",\"EmailAddress\":\"" + emailAddress + "\"}]}"));
    }

    private void hayFoto() {
        when(fotos.leer(NOMBRE_FOTO)).thenReturn(Optional.of(new Foto(JPEG, MediaType.IMAGE_JPEG)));
    }

    private void pendientes(Guia... guias) {
        when(guiaRepository.findByCorreoEstado(EstadoCorreo.PENDIENTE)).thenReturn(List.of(guias));
    }

    private MimeMessage enviado() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    // --- programar ---

    @Test
    void laEntregaDejaElCorreoPorEnviar() {
        Guia guia = porEnviar();
        guia.setCorreoEstado(null);

        servicio().programar(guia);

        assertEquals(EstadoCorreo.PENDIENTE, guia.getCorreoEstado());
        assertEquals(0, guia.getCorreoIntentos());
    }

    @Test
    void apagadoNoProgramaNada() {
        // Si las entregas quedaran pendientes con el correo apagado, encenderlo después le
        // escribiría de golpe a cada cliente por despachos de hace días.
        CorreoDespachoConfig apagado = new CorreoDespachoConfig();
        Guia guia = porEnviar();
        guia.setCorreoEstado(null);

        servicio(apagado).programar(guia);

        assertNull(guia.getCorreoEstado());
    }

    @Test
    void apagadoNiRevisaNiEnvia() {
        servicio(new CorreoDespachoConfig()).enviarPendientes(AHORA);

        verify(guiaRepository, never()).findByCorreoEstado(any());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    // --- envío ---

    @Test
    void vaAlClienteConCopiaOcultaInternaYLaFotoAdjunta() throws Exception {
        Guia guia = porEnviar();
        pendientes(guia);
        sapDiceQueElClienteTiene("compras@andes.cl");
        hayFoto();

        servicio().enviarPendientes(AHORA);

        MimeMessage mensaje = enviado();
        assertEquals(List.of("compras@andes.cl"), direcciones(mensaje, Message.RecipientType.TO));
        // Oculta: el cliente no tiene por qué ver las casillas internas.
        assertEquals(List.of("logistica@calimport.cl"), direcciones(mensaje, Message.RecipientType.BCC));
        assertEquals("noreply@calimport.cl", ((InternetAddress) mensaje.getFrom()[0]).getAddress());
        assertEquals("Despacho entregado · Guía 187350 · Calimport", mensaje.getSubject());
        assertEquals(List.of("entrega-guia-187350.jpg"), adjuntos(mensaje));
    }

    @Test
    void laFotoVaTambienDentroDelCuerpoJuntoAlLogo() throws Exception {
        pendientes(porEnviar());
        sapDiceQueElClienteTiene("compras@andes.cl");
        hayFoto();

        servicio().enviarPendientes(AHORA);

        // Como en HES: incrustada para verla sin abrir nada, y adjunta para guardarla.
        assertEquals(List.of("<" + MensajeDespacho.CID_LOGO + ">", "<" + MensajeDespacho.CID_FOTO + ">"),
                incrustadas(enviado()));
    }

    @Test
    void alEnviarQuedaRegistradoAQuienYCuando() {
        Guia guia = porEnviar();
        pendientes(guia);
        sapDiceQueElClienteTiene("compras@andes.cl; pagos@andes.cl");
        hayFoto();

        servicio().enviarPendientes(AHORA);

        assertEquals(EstadoCorreo.ENVIADO, guia.getCorreoEstado());
        assertEquals("compras@andes.cl, pagos@andes.cl", guia.getCorreoDestino());
        assertEquals(AHORA, guia.getCorreoFecha());
        assertEquals(1, guia.getCorreoIntentos());
        assertNull(guia.getCorreoMotivo());
        verify(guiaRepository).guardarCorreo(guia);
    }

    @Test
    void laCopiaInternaNoRepiteUnaCasillaQueYaVaAlCliente() throws Exception {
        pendientes(porEnviar());
        sapDiceQueElClienteTiene("logistica@calimport.cl");
        hayFoto();

        servicio().enviarPendientes(AHORA);

        assertTrue(direcciones(enviado(), Message.RecipientType.BCC).isEmpty());
    }

    @Test
    void conCasillaDeRespuestaElClienteRespondeAEllaYNoAlNoreply() throws Exception {
        CorreoDespachoConfig config = encendido();
        config.setResponderA("logistica@calimport.cl");
        pendientes(porEnviar());
        sapDiceQueElClienteTiene("compras@andes.cl");
        hayFoto();

        servicio(config).enviarPendientes(AHORA);

        MimeMessage mensaje = enviado();
        assertEquals("logistica@calimport.cl", ((InternetAddress) mensaje.getReplyTo()[0]).getAddress());
    }

    @Test
    void sinCasillaDeRespuestaNoLlevaReplyTo() throws Exception {
        pendientes(porEnviar());
        sapDiceQueElClienteTiene("compras@andes.cl");
        hayFoto();

        servicio().enviarPendientes(AHORA);

        assertNull(enviado().getHeader("Reply-To"));
    }

    @Test
    void siLaFotoNoApareceElAvisoSaleIgualSinAdjunto() throws Exception {
        Guia guia = porEnviar();
        pendientes(guia);
        sapDiceQueElClienteTiene("compras@andes.cl");
        when(fotos.leer(NOMBRE_FOTO)).thenReturn(Optional.empty());

        servicio().enviarPendientes(AHORA);

        assertTrue(adjuntos(enviado()).isEmpty());
        assertEquals(EstadoCorreo.ENVIADO, guia.getCorreoEstado());
    }

    // --- cliente sin correo ---

    @Test
    void clienteSinCorreoEnSapNoSeEnviaYQuedaDichoPorQue() {
        Guia guia = porEnviar();
        pendientes(guia);
        sapDiceQueElClienteTiene(null);

        servicio().enviarPendientes(AHORA);

        verify(mailSender, never()).send(any(MimeMessage.class));
        // No es una falla que se arregle reintentando: hay que cargar el correo en la ficha.
        assertEquals(EstadoCorreo.SIN_CORREO, guia.getCorreoEstado());
        assertTrue(guia.getCorreoMotivo().contains(CARD_CODE), guia.getCorreoMotivo());
        verify(guiaRepository).guardarCorreo(guia);
    }

    @Test
    void unaGuiaQueNoEstaEnSapQuedaSinCorreo() {
        // Las creadas a mano en la app (POST /api/guias) no tienen documento en SAP.
        Guia guia = porEnviar();
        pendientes(guia);
        when(sapClient.fetchClienteDeGuia(DOC_ENTRY)).thenReturn(json("{\"value\":[]}"));

        servicio().enviarPendientes(AHORA);

        verify(sapClient, never()).fetchCorreoDelCliente(any());
        assertEquals(EstadoCorreo.SIN_CORREO, guia.getCorreoEstado());
        assertTrue(guia.getCorreoMotivo().contains("SAP"));
    }

    // --- fallas y reintentos ---

    @Test
    void siSapNoContestaQuedaParaReintento() {
        Guia guia = porEnviar();
        pendientes(guia);
        when(sapClient.fetchClienteDeGuia(DOC_ENTRY)).thenThrow(new RuntimeException("Connection refused"));

        servicio().enviarPendientes(AHORA);

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertEquals(EstadoCorreo.PENDIENTE, guia.getCorreoEstado());
        assertEquals(1, guia.getCorreoIntentos());
        assertEquals(AHORA, guia.getCorreoFecha());
        assertTrue(guia.getCorreoMotivo().contains("SAP"), guia.getCorreoMotivo());
        verify(guiaRepository).guardarCorreo(guia);
    }

    @Test
    void siElServidorDeCorreoFallaQuedaParaReintento() {
        Guia guia = porEnviar();
        pendientes(guia);
        sapDiceQueElClienteTiene("compras@andes.cl");
        hayFoto();
        doThrow(new MailSendException("421 try again later")).when(mailSender).send(any(MimeMessage.class));

        servicio().enviarPendientes(AHORA);

        assertEquals(EstadoCorreo.PENDIENTE, guia.getCorreoEstado());
        assertEquals(1, guia.getCorreoIntentos());
        assertTrue(guia.getCorreoMotivo().contains("421"), guia.getCorreoMotivo());
    }

    @Test
    void alAgotarLosIntentosQuedaFallido() {
        // Tope a propósito: la cuota de Resend la comparten otros sistemas de Calimport.
        CorreoDespachoConfig config = encendido();
        config.setMaxIntentos(3);
        Guia guia = porEnviar();
        guia.setCorreoIntentos(2);
        guia.setCorreoFecha(AHORA.minus(Duration.ofHours(1)));
        pendientes(guia);
        when(sapClient.fetchClienteDeGuia(DOC_ENTRY)).thenThrow(new RuntimeException("Connection refused"));

        servicio(config).enviarPendientes(AHORA);

        assertEquals(EstadoCorreo.FALLIDO, guia.getCorreoEstado());
        assertEquals(3, guia.getCorreoIntentos());
    }

    @Test
    void entreIntentosEsperaCadaVezMasHastaMediaHora() {
        assertEquals(Duration.ofMinutes(1), CorreoDespachoService.espera(1));
        assertEquals(Duration.ofMinutes(2), CorreoDespachoService.espera(2));
        assertEquals(Duration.ofMinutes(4), CorreoDespachoService.espera(3));
        assertEquals(Duration.ofMinutes(16), CorreoDespachoService.espera(5));
        assertEquals(Duration.ofMinutes(30), CorreoDespachoService.espera(6));
        assertEquals(Duration.ofMinutes(30), CorreoDespachoService.espera(40));
    }

    @Test
    void unaGuiaQueFalloHacePocoNoSeReintentaTodavia() {
        Guia guia = porEnviar();
        guia.setCorreoIntentos(3);
        guia.setCorreoFecha(AHORA.minus(Duration.ofMinutes(3)));
        pendientes(guia);

        servicio().enviarPendientes(AHORA);

        verify(sapClient, never()).fetchClienteDeGuia(anyInt());
        verify(guiaRepository, never()).guardarCorreo(any());
    }

    @Test
    void cumplidaLaEsperaSeReintenta() {
        Guia guia = porEnviar();
        guia.setCorreoIntentos(3);
        guia.setCorreoFecha(AHORA.minus(Duration.ofMinutes(4)));
        pendientes(guia);
        sapDiceQueElClienteTiene("compras@andes.cl");
        hayFoto();

        servicio().enviarPendientes(AHORA);

        assertEquals(EstadoCorreo.ENVIADO, guia.getCorreoEstado());
        assertEquals(4, guia.getCorreoIntentos());
    }

    @Test
    void unaGuiaQueRevientaNoFrenaALasDemas() {
        Guia primera = porEnviar();
        Guia segunda = porEnviar();
        segunda.setId(11L);
        pendientes(primera, segunda);
        sapDiceQueElClienteTiene("compras@andes.cl");
        hayFoto();
        doThrow(new RuntimeException("base caída")).doNothing().when(guiaRepository).guardarCorreo(any());

        servicio().enviarPendientes(AHORA);

        verify(guiaRepository).guardarCorreo(segunda);
    }

    // --- desvío de prueba ---

    @Test
    void conDesvioTodoVaAlDesvioSinCopiaYDiceAQuienHabriaIdo() throws Exception {
        CorreoDespachoConfig config = encendido();
        config.setDesvio("pruebas@calimport.cl");
        Guia guia = porEnviar();
        pendientes(guia);
        sapDiceQueElClienteTiene("compras@andes.cl");
        hayFoto();

        servicio(config).enviarPendientes(AHORA);

        MimeMessage mensaje = enviado();
        assertEquals(List.of("pruebas@calimport.cl"), direcciones(mensaje, Message.RecipientType.TO));
        assertTrue(direcciones(mensaje, Message.RecipientType.BCC).isEmpty());
        assertTrue(mensaje.getSubject().startsWith("[PRUEBA] "));
        assertTrue(texto(mensaje).contains("compras@andes.cl"), texto(mensaje));
        assertEquals("pruebas@calimport.cl", guia.getCorreoDestino());
    }

    @Test
    void conDesvioSaleAunqueSapNoConteste() {
        // En local no hay SAP: el desvío es lo que permite probar el correo completo en el PC.
        CorreoDespachoConfig config = encendido();
        config.setDesvio("pruebas@calimport.cl");
        Guia guia = porEnviar();
        pendientes(guia);
        when(sapClient.fetchClienteDeGuia(DOC_ENTRY)).thenThrow(new RuntimeException("Connection refused"));
        hayFoto();

        servicio(config).enviarPendientes(AHORA);

        verify(mailSender).send(any(MimeMessage.class));
        assertEquals(EstadoCorreo.ENVIADO, guia.getCorreoEstado());
    }

    // --- configuración al arrancar ---

    @Test
    void encendidoSinClaveSmtpNoArranca() {
        // Mejor enterarse al desplegar que con una semana de avisos sin salir.
        assertThrows(IllegalStateException.class, () -> new CorreoDespachoService(guiaRepository, sapClient,
                fotos, mailSender, encendido(), sap("CALIMPORT_PRODUCTIVO"), " "));
    }

    @Test
    void contraLaBaseDeTestSinDesvioNoArranca() {
        // TEST es una copia de producción: sus clientes tienen los correos reales.
        assertThrows(IllegalStateException.class, () -> new CorreoDespachoService(guiaRepository, sapClient,
                fotos, mailSender, encendido(), sap("CALIMPORT_TEST"), "re_clave_de_prueba"));
    }

    @Test
    void contraLaBaseDeTestConDesvioArranca() {
        CorreoDespachoConfig config = encendido();
        config.setDesvio("pruebas@calimport.cl");

        new CorreoDespachoService(guiaRepository, sapClient, fotos, mailSender, config,
                sap("CALIMPORT_TEST"), "re_clave_de_prueba");
    }

    @Test
    void unDesvioMalEscritoNoArranca() {
        // Ignorarlo mandaría los correos de prueba a los clientes de verdad.
        CorreoDespachoConfig config = encendido();
        config.setDesvio("pruebas@");

        assertThrows(IllegalStateException.class, () -> servicio(config));
    }

    @Test
    void apagadoArrancaSinClaveSmtp() {
        new CorreoDespachoService(guiaRepository, sapClient, fotos, mailSender, new CorreoDespachoConfig(),
                sap("CALIMPORT_TEST"), "");
    }

    // --- lectura del mensaje ---

    private static List<String> direcciones(MimeMessage mensaje, Message.RecipientType tipo) throws MessagingException {
        var lista = mensaje.getRecipients(tipo);
        return lista == null ? List.of()
                : Arrays.stream(lista).map(a -> ((InternetAddress) a).getAddress()).toList();
    }

    private static List<String> adjuntos(Part parte) throws MessagingException, IOException {
        List<String> nombres = new ArrayList<>();
        if (parte.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                nombres.addAll(adjuntos(multipart.getBodyPart(i)));
            }
        } else if (Part.ATTACHMENT.equalsIgnoreCase(parte.getDisposition())) {
            nombres.add(parte.getFileName());
        }
        return nombres;
    }

    /** Content-ID de las partes incrustadas (las que el HTML pide con cid:), en orden. */
    private static List<String> incrustadas(Part parte) throws MessagingException, IOException {
        List<String> ids = new ArrayList<>();
        if (parte.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                ids.addAll(incrustadas(multipart.getBodyPart(i)));
            }
        } else if (Part.INLINE.equalsIgnoreCase(parte.getDisposition())
                && parte instanceof jakarta.mail.internet.MimeBodyPart cuerpo && cuerpo.getContentID() != null) {
            ids.add(cuerpo.getContentID());
        }
        return ids;
    }

    /** Sin saveChanges() el tipo real de cada parte está en el DataHandler, no en el encabezado. */
    private static String texto(Part parte) throws MessagingException, IOException {
        Object contenido = parte.getContent();
        if (contenido instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String encontrado = texto(multipart.getBodyPart(i));
                if (encontrado != null) {
                    return encontrado;
                }
            }
            return null;
        }
        String tipo = parte.getDataHandler().getContentType().toLowerCase(Locale.ROOT);
        return tipo.startsWith("text/plain") ? (String) contenido : null;
    }
}
