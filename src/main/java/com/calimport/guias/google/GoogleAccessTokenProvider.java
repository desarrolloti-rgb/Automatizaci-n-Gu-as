package com.calimport.guias.google;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Component;

import com.google.auth.oauth2.GoogleCredentials;

/**
 * Token OAuth para las APIs de GCP que no aceptan API key (Route Optimization, Vertex AI).
 *
 * <p>Usa Application Default Credentials: en Cloud Run toma la cuenta de servicio del
 * servicio; en local, la de {@code gcloud auth application-default login} o la variable
 * {@code GOOGLE_APPLICATION_CREDENTIALS}. Las credenciales se cargan recién en la primera
 * llamada, para que la app arranque aunque no estén configuradas.
 */
@Component
public class GoogleAccessTokenProvider {

    private static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/cloud-platform");

    private volatile GoogleCredentials credentials;

    public String token() {
        try {
            GoogleCredentials c = credenciales();
            // refreshIfExpired reutiliza el token mientras siga vigente (dura ~1 hora).
            c.refreshIfExpired();
            return c.getAccessToken().getTokenValue();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron obtener credenciales de GCP: " + e.getMessage(), e);
        }
    }

    private GoogleCredentials credenciales() throws IOException {
        if (credentials == null) {
            synchronized (this) {
                if (credentials == null) {
                    credentials = GoogleCredentials.getApplicationDefault().createScoped(SCOPES);
                }
            }
        }
        return credentials;
    }
}
