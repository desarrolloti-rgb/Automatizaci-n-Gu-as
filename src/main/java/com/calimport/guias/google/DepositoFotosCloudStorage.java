package com.calimport.guias.google;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.calimport.guias.service.DepositoFotos;
import com.calimport.guias.utils.ApiException;

/**
 * Las fotos en un bucket de Cloud Storage ({@code fotos.bucket}), por la API JSON y con el
 * token de {@link GoogleAccessTokenProvider}: el mismo patrón que Route Optimization, sin el
 * SDK de Storage.
 *
 * <p>Son la evidencia de las entregas: en el bucket sobreviven a que se pierda la VM. La
 * cuenta de servicio de la VM solo puede crear y leer objetos de este bucket, no borrarlos
 * ni reemplazarlos (ver {@code deploy/crear-bucket.ps1}).
 */
@Component
@ConditionalOnProperty(name = "fotos.almacen", havingValue = "gcs")
public class DepositoFotosCloudStorage implements DepositoFotos {

    private static final Logger log = LoggerFactory.getLogger(DepositoFotosCloudStorage.class);

    private final String bucket;
    private final GoogleAccessTokenProvider tokenProvider;
    private final RestClient restClient;

    public DepositoFotosCloudStorage(@Value("${fotos.bucket:}") String bucket,
                                     GoogleAccessTokenProvider tokenProvider) {
        // Falla al arrancar y no en la primera entrega: sin bucket, el repartidor no podría
        // cerrar ninguna guía y se sabría recién en la calle.
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("fotos.almacen=gcs exige FOTOS_BUCKET");
        }
        this.bucket = bucket.trim();
        this.tokenProvider = tokenProvider;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        // Una foto pesa unos MB y la VM está en la misma región que el bucket: 30 s sobra.
        factory.setReadTimeout(30_000);
        this.restClient = RestClient.builder()
                .baseUrl("https://storage.googleapis.com")
                .requestFactory(factory)
                .build();
    }

    @Override
    public void escribir(String nombre, byte[] contenido, MediaType tipo) {
        try {
            restClient.post()
                    // ifGenerationMatch=0: solo si el objeto no existe. La evidencia no se
                    // reemplaza, y sin permiso de borrar tampoco se podría.
                    .uri("/upload/storage/v1/b/{bucket}/o?uploadType=media&name={nombre}&ifGenerationMatch=0",
                            bucket, nombre)
                    .header("Authorization", "Bearer " + tokenProvider.token())
                    .contentType(tipo)
                    .body(contenido)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException | IllegalStateException e) {
            log.error("No se pudo subir la foto {} a gs://{}: {}", nombre, bucket, e.getMessage());
            // 503 y no 500: al repartidor le dice que reintente, no que algo está roto.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo guardar la foto, intenta de nuevo en un momento");
        }
    }

    @Override
    public Optional<byte[]> leer(String nombre) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/storage/v1/b/{bucket}/o/{nombre}?alt=media", bucket, nombre)
                    .header("Authorization", "Bearer " + tokenProvider.token())
                    .retrieve()
                    .body(byte[].class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
