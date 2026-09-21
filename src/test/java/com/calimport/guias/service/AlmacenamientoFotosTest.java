package com.calimport.guias.service;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.calimport.guias.service.AlmacenamientoFotos.Foto;
import com.calimport.guias.service.AlmacenamientoFotos.FotoGuardada;
import com.calimport.guias.utils.ApiException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlmacenamientoFotosTest {

    /** Cabecera real de un JPEG seguida de bytes cualquiera. */
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};

    @TempDir
    Path directorio;

    private AlmacenamientoFotos almacenamiento;

    @BeforeEach
    void setUp() {
        almacenamiento = new AlmacenamientoFotos(directorio.toString());
    }

    @Test
    void guardaLaFotoYDevuelveUrlRelativaYHashDelContenido() throws Exception {
        FotoGuardada guardada = almacenamiento.guardar(12L, JPEG);

        assertTrue(guardada.urlFoto().matches("/api/fotos/guia-12-[0-9a-f-]{36}\\.jpg"));
        String nombre = guardada.urlFoto().substring("/api/fotos/".length());
        assertArrayEquals(JPEG, Files.readAllBytes(directorio.resolve(nombre)));
        // SHA-256 en hexadecimal: los 64 caracteres que admite la columna hash_foto.
        assertEquals(64, guardada.hashFoto().length());
        assertEquals(AlmacenamientoFotos.sha256(JPEG), guardada.hashFoto());
    }

    @Test
    void laFotoGuardadaSePuedeLeerConSuTipo() {
        String nombre = almacenamiento.guardar(12L, JPEG).urlFoto().substring("/api/fotos/".length());

        Foto foto = almacenamiento.leer(nombre).orElseThrow();

        assertArrayEquals(JPEG, foto.contenido());
        assertEquals(MediaType.IMAGE_JPEG, foto.tipo());
    }

    @Test
    void reconoceJpgPngYWebpPorSuContenido() {
        assertEquals("jpg", AlmacenamientoFotos.extensionSegunContenido(JPEG));
        assertEquals("png", AlmacenamientoFotos.extensionSegunContenido(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
        assertEquals("webp", AlmacenamientoFotos.extensionSegunContenido(
                new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));
    }

    @Test
    void rechazaUnArchivoQueNoEsImagenAunqueSeLlameFotoJpg() {
        // Lo que manda es el contenido, no el nombre ni el Content-Type que pone el cliente.
        ApiException e = assertThrows(ApiException.class,
                () -> almacenamiento.guardar(12L, "<script>alert(1)</script>".getBytes()));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void rechazaUnArchivoVacio() {
        ApiException e = assertThrows(ApiException.class, () -> almacenamiento.guardar(12L, new byte[0]));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void noLeeFueraDelDirectorioDeFotos() throws Exception {
        Files.writeString(directorio.getParent().resolve("secreto.txt"), "no");

        assertTrue(almacenamiento.leer("../secreto.txt").isEmpty());
        assertTrue(almacenamiento.leer("guia-1-../../secreto.jpg").isEmpty());
    }

    @Test
    void unNombreValidoQueNoExisteDevuelveVacio() {
        assertTrue(almacenamiento.leer("guia-1-00000000-0000-0000-0000-000000000000.jpg").isEmpty());
    }
}
