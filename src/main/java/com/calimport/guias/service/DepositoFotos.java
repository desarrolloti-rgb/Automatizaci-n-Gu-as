package com.calimport.guias.service;

import java.util.Optional;

import org.springframework.http.MediaType;

/**
 * Dónde quedan los bytes de las fotos de entrega. {@link AlmacenamientoFotos} valida, nombra
 * y calcula el hash; esto solo escribe y lee.
 *
 * <p>Dos implementaciones, elegidas con {@code fotos.almacen}: {@link DepositoFotosDisco}
 * (defecto, para local y tests) y {@link com.calimport.guias.google.DepositoFotosCloudStorage}
 * (la VM). En producción van a Cloud Storage porque son la evidencia de lo que pasó en
 * terreno y el disco de la VM se pierde con la VM.
 *
 * <p>Una foto se escribe una sola vez y no se reemplaza: el nombre lleva un UUID y la
 * implementación de Cloud Storage lo exige ({@code ifGenerationMatch=0}).
 */
public interface DepositoFotos {

    void escribir(String nombre, byte[] contenido, MediaType tipo);

    /** Vacío si no existe; el nombre ya viene validado por {@link AlmacenamientoFotos}. */
    Optional<byte[]> leer(String nombre);
}
