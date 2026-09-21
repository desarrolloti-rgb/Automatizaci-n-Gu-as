package com.calimport.guias.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.calimport.guias.utils.ApiException;

/**
 * Guarda las fotos de entrega en disco y calcula su hash.
 *
 * <p>El hash lo calcula el servidor sobre los bytes recibidos, no el celular: así el valor
 * que queda en la guía corresponde con certeza al archivo guardado.
 *
 * <p>Sirve en local y en una VM. En Cloud Run el disco es efímero, así que ahí habrá que
 * cambiar esta clase por una que escriba en Cloud Storage; quien la usa no cambia.
 */
@Service
public class AlmacenamientoFotos {

    /** Solo nombres generados por {@link #guardar}: impide leer fuera del directorio con "../". */
    private static final Pattern NOMBRE_VALIDO =
            Pattern.compile("^guia-\\d+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    static final String PREFIJO_URL = "/api/fotos/";

    private final Path directorio;

    public AlmacenamientoFotos(@Value("${fotos.directorio}") String directorio) {
        this.directorio = Path.of(directorio).toAbsolutePath().normalize();
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
        try {
            Files.createDirectories(directorio);
            Files.write(directorio.resolve(nombre), contenido);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar la foto " + nombre, e);
        }
        return new FotoGuardada(PREFIJO_URL + nombre, sha256(contenido));
    }

    public Optional<Foto> leer(String nombre) {
        if (nombre == null || !NOMBRE_VALIDO.matcher(nombre).matches()) {
            return Optional.empty();
        }
        Path archivo = directorio.resolve(nombre);
        if (!Files.isRegularFile(archivo)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Foto(Files.readAllBytes(archivo), tipoSegunExtension(nombre)));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la foto " + nombre, e);
        }
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
