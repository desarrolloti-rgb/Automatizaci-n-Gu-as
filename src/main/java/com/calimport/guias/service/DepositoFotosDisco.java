package com.calimport.guias.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Las fotos en una carpeta del disco ({@code fotos.directorio}). Es lo de local y los tests:
 * en la VM no sirve de respaldo, porque si se pierde la máquina se pierden con ella.
 */
@Component
@ConditionalOnProperty(name = "fotos.almacen", havingValue = "disco", matchIfMissing = true)
public class DepositoFotosDisco implements DepositoFotos {

    private final Path directorio;

    public DepositoFotosDisco(@Value("${fotos.directorio}") String directorio) {
        this.directorio = Path.of(directorio).toAbsolutePath().normalize();
    }

    @Override
    public void escribir(String nombre, byte[] contenido, MediaType tipo) {
        try {
            Files.createDirectories(directorio);
            Files.write(directorio.resolve(nombre), contenido);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar la foto " + nombre, e);
        }
    }

    @Override
    public Optional<byte[]> leer(String nombre) {
        Path archivo = directorio.resolve(nombre);
        if (!Files.isRegularFile(archivo)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(archivo));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la foto " + nombre, e);
        }
    }
}
