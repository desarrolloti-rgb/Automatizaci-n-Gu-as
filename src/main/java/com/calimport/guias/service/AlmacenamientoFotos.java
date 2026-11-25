package com.calimport.guias.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.calimport.guias.utils.ApiException;

/**
 * Valida, nombra y calcula el hash de la evidencia de entrega: las fotos del repartidor y el PDF
 * de un transportista externo. Dónde quedan los bytes lo decide {@link DepositoFotos}: disco en
 * local, Cloud Storage en la VM.
 *
 * <p>El hash lo calcula el servidor sobre los bytes recibidos, no el celular: así el valor
 * que queda en la guía corresponde con certeza al archivo guardado.
 */
@Service
public class AlmacenamientoFotos {

    /**
     * Solo nombres generados por {@link #guardar} y {@link #guardarPdf}: impide leer fuera del
     * directorio con "../".
     */
    private static final Pattern NOMBRE_VALIDO =
            Pattern.compile("^guia-\\d+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp|pdf)$");

    /**
     * El PDF más grande que se acepta de un transportista. El albarán de Samex pesa ~450 KB (una
     * página de texto y dos fotos); 15 MB deja margen para varias fotos sin aceptar cualquier cosa.
     */
    static final int TOPE_PDF = 15 * 1024 * 1024;

    /** La que usa la app, con token. Es la que queda guardada en la guía. */
    static final String PREFIJO_URL = "/api/fotos/";

    /**
     * La que va a SAP, sin token: quien la abre desde SAP no tiene sesión en la app. La
     * protege el UUID del nombre, que no se puede adivinar. Corta a propósito: U_UrlFoto
     * mide 100, y dominio + esta ruta + nombre con un id de 6 dígitos suma 96.
     */
    public static final String PREFIJO_URL_PUBLICA = "/api/evidencia/";

    private final DepositoFotos deposito;

    public AlmacenamientoFotos(DepositoFotos deposito) {
        this.deposito = deposito;
    }

    /**
     * @param url relativa al servidor ({@code /api/fotos/...}), para que funcione igual
     *     desde localhost que desde la IP del PC en el celular
     */
    public record FotoGuardada(String urlFoto, String hashFoto) {
    }

    public record Foto(byte[] contenido, MediaType tipo) {
    }

    public FotoGuardada guardar(Long guiaId, byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La foto está vacía");
        }
        // Se mira el contenido y no el Content-Type ni la extensión, que los pone el cliente.
        String extension = extensionSegunContenido(contenido);
        if (extension == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El archivo debe ser una imagen JPG, PNG o WEBP");
        }

        String nombre = "guia-" + guiaId + "-" + UUID.randomUUID() + "." + extension;
        deposito.escribir(nombre, contenido, tipoSegunExtension(nombre));
        return new FotoGuardada(PREFIJO_URL + nombre, sha256(contenido));
    }

    /**
     * La evidencia de un transportista externo: el PDF que baja su portal (el albarán de Samex,
     * con la prueba de entrega y las fotos de la guía firmada). Se guarda tal cual llegó, con su
     * SHA-256, igual que una foto: es la evidencia. Como el de las fotos, el nombre lleva un UUID
     * y no se reemplaza nunca.
     *
     * <p>No es la puerta del repartidor: {@link #guardar} sigue aceptando solo imágenes.
     */
    public FotoGuardada guardarPdf(Long guiaId, byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El PDF está vacío");
        }
        if (contenido.length > TOPE_PDF) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El PDF supera los " + TOPE_PDF / (1024 * 1024) + " MB");
        }
        // Por el contenido, como las fotos: todo PDF empieza con "%PDF-".
        if (!esPdf(contenido)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El archivo debe ser un PDF");
        }
        String nombre = "guia-" + guiaId + "-" + UUID.randomUUID() + ".pdf";
        deposito.escribir(nombre, contenido, MediaType.APPLICATION_PDF);
        return new FotoGuardada(PREFIJO_URL + nombre, sha256(contenido));
    }

    static boolean esPdf(byte[] b) {
        return b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-';
    }

    public Optional<Foto> leer(String nombre) {
        if (nombre == null || !NOMBRE_VALIDO.matcher(nombre).matches()) {
            return Optional.empty();
        }
        return deposito.leer(nombre).map(bytes -> new Foto(bytes, tipoSegunExtension(nombre)));
    }

    /**
     * El nombre del archivo, a partir de la URL que quedó en la guía, para leerlo con
     * {@link #leer}. Null si no es una foto de esta app.
     */
    public static String nombreDe(String urlFoto) {
        if (urlFoto == null || !urlFoto.startsWith(PREFIJO_URL)) {
            return null;
        }
        return urlFoto.substring(PREFIJO_URL.length());
    }

    /**
     * La ruta sin token de una foto guardada, a partir de la que quedó en la guía. Null si
     * no es una foto de esta app (una URL que no empieza con {@link #PREFIJO_URL}).
     */
    public static String rutaPublica(String urlFoto) {
        String nombre = nombreDe(urlFoto);
        return nombre == null ? null : PREFIJO_URL_PUBLICA + nombre;
    }

    static String extensionSegunContenido(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static MediaType tipoSegunExtension(String nombre) {
        if (nombre.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (nombre.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (nombre.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }

    static String sha256(byte[] contenido) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
