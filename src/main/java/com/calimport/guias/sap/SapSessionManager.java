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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
                    List<String> cookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
                    if (cookies == null || cookies.isEmpty()) {
                        throw new RuntimeException("No hay encabezado Set-Cookie en la respuesta del login de SAP");
                    }
                    return armarCookie(cookies);
                });

        sessionCookie = response;
        log.info("SAP login successful, session acquired");
        return sessionCookie;
    }

    /**
     * Junta <b>todas</b> las cookies del login en un solo encabezado {@code Cookie}.
     *
     * <p>El Service Layer devuelve dos: {@code B1SESSION}, que identifica la sesión, y
     * {@code ROUTEID}, que identifica el nodo del clúster que la atiende. Quedarse solo con
     * la primera —lo que hacía este método antes, con {@code getFirst()}— parece funcionar,
     * porque las lecturas contestan igual desde cualquier nodo. Las <b>escrituras no</b>:
     * la transacción vive en el nodo que abrió la sesión, y sin {@code ROUTEID} el PATCH
     * aterriza en otro y muere con "Could not commit transaction: Error -1".
     *
     * <p>Costó encontrarlo porque SAP no dice que falte la cookie: informa un error de
     * transacción, que parece un problema de datos. Dashboard nunca lo vio porque solo lee.
     *
     * <p>De cada {@code Set-Cookie} se toma únicamente el {@code nombre=valor} inicial: los
     * atributos ({@code HttpOnly}, {@code Secure}, {@code SameSite}) son instrucciones para
     * un navegador y no van en la petición.
     */
    static String armarCookie(List<String> setCookies) {
        return setCookies.stream()
                .map(c -> c.split(";", 2)[0].trim())
                .filter(c -> !c.isEmpty())
                .collect(Collectors.joining("; "));
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
