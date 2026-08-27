package com.calimport.guias.sap;

import com.calimport.guias.config.SapConfig;
import jakarta.annotation.PostConstruct;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.io.SocketConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import javax.net.ssl.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Maneja la sesión (cookie) contra SAP Service Layer: login, renovación automática al
 * expirar, y reintento de la llamada que falló por sesión vencida. Calcado del mismo
 * componente en Dashboard — mismo Service Layer, misma mecánica de sesión.
 */
@Component
public class SapSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SapSessionManager.class);

    private final SapConfig config;
    private volatile String sessionCookie;

    private RestClient restClient;

    public SapSessionManager(SapConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        RestClient.Builder builder = RestClient.builder().baseUrl(config.getBaseUrl());
        if (config.isTrustSelfSigned()) {
            log.warn("trust-self-signed=true: la verificacion de certificados SSL esta deshabilitada. "
                    + "Usar solo en desarrollo. En produccion, configurar trust-self-signed=false "
                    + "y usar un certificado valido.");
            builder = builder.requestFactory(createTrustAllRequestFactory());
        } else {
            builder = builder.requestFactory(new HttpComponentsClientHttpRequestFactory());
        }
        this.restClient = builder.build();
    }

    private HttpComponentsClientHttpRequestFactory createTrustAllRequestFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                    sslContext, NoopHostnameVerifier.INSTANCE);

            var connManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(sslSocketFactory)
                    .setDefaultSocketConfig(SocketConfig.custom()
                            .setSoTimeout(30, TimeUnit.SECONDS)
                            .build())
                    .build();

            var httpClient = HttpClients.custom()
                    .setConnectionManager(connManager)
                    .disableCookieManagement()
                    .disableRedirectHandling()
                    .evictIdleConnections(org.apache.hc.core5.util.TimeValue.of(30, TimeUnit.SECONDS))
                    .build();

            return new HttpComponentsClientHttpRequestFactory(httpClient);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Fallo al configurar el trust-all de SSL", e);
        }
    }

    private static final TrustManager[] trustAllCerts = new TrustManager[]{
        new X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        }
    };

    public String login() {
        log.info("Logeando a SAP Service Layer...");
        Map<String, String> loginBody = Map.of(
            "CompanyDB", config.getCompanyDb(),
            "UserName", config.getUsername(),
            "Password", config.getPassword()
        );

        var response = restClient.post()
                .uri("/Login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(loginBody)
                .exchange((req, res) -> {
                    if (res.getStatusCode() != HttpStatus.OK
                            && res.getStatusCode() != HttpStatus.CREATED
                            && res.getStatusCode() != HttpStatus.NO_CONTENT) {
                        throw new RuntimeException("Fallo en el login de SAP: " + res.getStatusCode());
                    }
                    String cookie = res.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
                    if (cookie == null) {
                        throw new RuntimeException("No hay encabezado Set-Cookie en la respuesta del login de SAP");
                    }
                    return cookie;
                });

        sessionCookie = response;
        log.info("SAP login successful, session acquired");
        return sessionCookie;
    }

    public String currentCookieOrLogin() {
        if (sessionCookie == null) {
            synchronized (this) {
                if (sessionCookie == null) {
                    return login();
                }
            }
        }
        return sessionCookie;
    }

    public <T> T executeWithSession(SapRequest<T> call) {
        String cookie = currentCookieOrLogin();
        try {
            return call.execute(cookie);
        } catch (HttpClientErrorException.Unauthorized | SapUnauthorizedException e) {
            log.warn("Sesion SAP expirada, renovando...");
            return call.execute(renovarSesion(cookie));
        }
    }

    private synchronized String renovarSesion(String cookieUsada) {
        if (sessionCookie != null && !sessionCookie.equals(cookieUsada)) {
            return sessionCookie;
        }
        return login();
    }

    public RestClient getRestClient() {
        return restClient;
    }

    @FunctionalInterface
    public interface SapRequest<T> {
        T execute(String sessionCookie);
    }

    public static class SapUnauthorizedException extends RuntimeException {
        public SapUnauthorizedException(String message) {
            super(message);
        }
    }
}
