package com.calimport.guias.controller;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.calimport.guias.config.IntegracionesSecurityConfig;
import com.calimport.guias.model.Rol;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.service.EnviosExternosService;
import com.calimport.guias.service.EnviosExternosService.EvidenciaGuardada;
import com.calimport.guias.service.EnviosExternosService.Pendiente;
import com.calimport.guias.service.EnviosExternosService.ResultadoBusqueda;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recorre la cadena de seguridad real de /api/integraciones: el decodificador con el validador de
 * producción, el rol INTEGRACION y el @PreAuthorize. Solo cambia de dónde salen las llaves: en
 * producción son las públicas de Google; acá, una llave generada para el test, así nada sale a la red.
 */
@SpringBootTest(properties = {
        "guias.integraciones.audiencia=" + IntegracionSamexControllerTest.AUDIENCIA,
        "guias.integraciones.cuentas=" + IntegracionSamexControllerTest.CUENTA})
@AutoConfigureMockMvc
class IntegracionSamexControllerTest {

    static final String AUDIENCIA = "https://apps.calimport.cl/gd";
    static final String CUENTA = "samex-runner@charming-hearth-503417-i0.iam.gserviceaccount.com";

    private static final KeyPair LLAVE = generarLlave();
    private static final KeyPair OTRA_LLAVE = generarLlave();

    @TestConfiguration
    static class LlavesDePrueba {

        @Bean
        @Primary
        JwtDecoder decodificadorDePrueba(@Value("${guias.integraciones.audiencia}") String audiencia,
                                         @Value("${guias.integraciones.cuentas}") List<String> cuentas) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) LLAVE.getPublic()).build();
            decoder.setJwtValidator(IntegracionesSecurityConfig.validador(audiencia, cuentas));
            return decoder;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private EnviosExternosService envios;

    private static KeyPair generarLlave() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            return generador.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Un ID token como el que entrega el servidor de metadatos de Cloud Run (format=full). */
    private static String token(KeyPair llave, Consumer<JWTClaimsSet.Builder> ajuste) throws Exception {
        Instant ahora = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(AUDIENCIA)
                .subject("104857201234567890123")
                .claim("email", CUENTA)
                .claim("email_verified", true)
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(3600)));
        ajuste.accept(claims);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("prueba").build(),
                claims.build());
        jwt.sign(new RSASSASigner(llave.getPrivate()));
        return "Bearer " + jwt.serialize();
    }

    private static String tokenDelLector() throws Exception {
        return token(LLAVE, c -> { });
    }

    // --- lo que puede hacer el lector ---

    @Test
    void conElTokenDelLectorDevuelveLasPendientes() throws Exception {
        when(envios.pendientes("SAMEX")).thenReturn(List.of(new Pendiente(1L, 311994L, "999905761651")));

        mockMvc.perform(get("/api/integraciones/samex/pendientes").header("Authorization", tokenDelLector()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].guiaId").value(1))
                .andExpect(jsonPath("$[0].folio").value(311994))
                .andExpect(jsonPath("$[0].envio").value("999905761651"));
    }

    @Test
    void registrarPasaLosResultadosAlServicioYDevuelveLosPdfQueFaltan() throws Exception {
        when(envios.registrar(eq("SAMEX"), any())).thenReturn(List.of("999905761651"));

        mockMvc.perform(post("/api/integraciones/samex/envios")
                        .header("Authorization", tokenDelLector())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"guiaId": 1, "folio": 311994, "envios": [{
                                    "numero": "999905761651",
                                    "estadoPortal": "Entregada movilidad 23/11/2026",
                                    "referencias": ["TRASLADO SUCURSAL", "311994"],
                                    "fechaEntrega": "2026-11-23T13:03:56Z"}]}]"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.necesitanEvidencia[0]").value("999905761651"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ResultadoBusqueda>> resultados = ArgumentCaptor.forClass(List.class);
        verify(envios).registrar(eq("SAMEX"), resultados.capture());
        ResultadoBusqueda resultado = resultados.getValue().get(0);
        assertEquals(311994L, resultado.folio());
        assertEquals(List.of("TRASLADO SUCURSAL", "311994"), resultado.envios().get(0).referencias());
        assertEquals(Instant.parse("2026-11-23T13:03:56Z"), resultado.envios().get(0).fechaEntrega());
    }

    @Test
    void elLectorSubeElPdfDeUnaEntregaComoCuerpoDeLaPeticion() throws Exception {
        byte[] pdf = "%PDF-1.4\n%albaran".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        when(envios.guardarEvidencia(eq("SAMEX"), eq("999905761651"), any()))
                .thenReturn(new EvidenciaGuardada("/api/fotos/guia-1-abc.pdf", "hash", false));

        mockMvc.perform(put("/api/integraciones/samex/envios/999905761651/evidencia")
                        .header("Authorization", tokenDelLector())
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(pdf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenciaUrl").value("/api/fotos/guia-1-abc.pdf"))
                .andExpect(jsonPath("$.guiaCerrada").value(false));

        ArgumentCaptor<byte[]> cuerpo = ArgumentCaptor.forClass(byte[].class);
        verify(envios).guardarEvidencia(eq("SAMEX"), eq("999905761651"), cuerpo.capture());
        assertEquals(new String(pdf, java.nio.charset.StandardCharsets.US_ASCII),
                new String(cuerpo.getValue(), java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void subirUnPdfSinTokenEs401() throws Exception {
        mockMvc.perform(put("/api/integraciones/samex/envios/999905761651/evidencia")
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(new byte[] {'%', 'P', 'D', 'F', '-'}))
                .andExpect(status().isUnauthorized());

        verify(envios, never()).guardarEvidencia(any(), any(), any());
    }

    // --- quién no pasa ---

    @Test
    void sinTokenEs401() throws Exception {
        mockMvc.perform(get("/api/integraciones/samex/pendientes"))
                .andExpect(status().isUnauthorized());

        verify(envios, never()).pendientes(any());
    }

    @Test
    void otraCuentaDeGoogleEs401() throws Exception {
        String ajena = token(LLAVE, c -> c.claim("email", "otra-cuenta@charming-hearth-503417-i0.iam.gserviceaccount.com"));

        mockMvc.perform(get("/api/integraciones/samex/pendientes").header("Authorization", ajena))
                .andExpect(status().isUnauthorized());
        verify(envios, never()).pendientes(any());
    }

    @Test
    void unTokenParaOtraAudienciaEs401() throws Exception {
        String dePrueba = token(LLAVE, c -> c.audience("https://apps.calimport.cl/gd-prueba"));

        mockMvc.perform(get("/api/integraciones/samex/pendientes").header("Authorization", dePrueba))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unTokenVencidoEs401() throws Exception {
        Instant hace2h = Instant.now().minusSeconds(7200);
        String vencido = token(LLAVE, c -> c.issueTime(Date.from(hace2h))
                .expirationTime(Date.from(hace2h.plusSeconds(3600))));

        mockMvc.perform(get("/api/integraciones/samex/pendientes").header("Authorization", vencido))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unTokenFirmadoConOtraLlaveEs401() throws Exception {
        String falsificado = token(OTRA_LLAVE, c -> { });

        mockMvc.perform(get("/api/integraciones/samex/pendientes").header("Authorization", falsificado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void elTokenDeUnJefeDeBodegaNoSirveParaLasIntegraciones() throws Exception {
        String jefe = "Bearer " + jwtTokenProvider.generateToken("jefe@calimport.cl", 9, "Jefe Bodega", Rol.JEFE_BODEGA);

        mockMvc.perform(get("/api/integraciones/samex/pendientes").header("Authorization", jefe))
                .andExpect(status().isUnauthorized());
        verify(envios, never()).pendientes(any());
    }

    @Test
    void elTokenDelLectorNoAbreElRestoDeLaApi() throws Exception {
        // Para la cadena de la app es un token cualquiera que no firmó ella: sin sesión.
        mockMvc.perform(get("/api/guias").header("Authorization", tokenDelLector()))
                .andExpect(status().isUnauthorized());
    }
}
