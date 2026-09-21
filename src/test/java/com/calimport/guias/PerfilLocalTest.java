package com.calimport.guias;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El MVP de punta a punta con el perfil local real: sin SAP y sin GCP. Lo único que se
 * cambia es la base y el directorio de fotos.
 *
 * <p>El perfil local apunta a un Postgres de desarrollo; acá se reemplaza por H2 en memoria
 * (con las mismas migraciones) para que {@code mvnw test} no dependa de que haya un Postgres
 * levantado ni ensucie la base con la que se está probando a mano. Por eso se fijan también
 * driver y credenciales: si solo se cambiara la URL, quedarían los de Postgres.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:perfil-local;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "fotos.directorio=target/test-fotos-local"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PerfilLocalTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(MvcResult resultado) throws Exception {
        return mapper.readTree(resultado.getResponse().getContentAsString());
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + json(r).get("token").asString();
    }

    @Test
    void laPantallaDePruebaSeSirveSinToken() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void unaContrasenaIncorrectaNoEntra() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"uno@calimport.local\",\"password\":\"mala\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void flujoCompletoBodegaAsignaYElRepartidorRetiraSubeFotoYEntrega() throws Exception {
        String jefe = login("jefe@calimport.local", "1234");
        String token = login("uno@calimport.local", "1234");
        String otro = login("dos@calimport.local", "1234");

        // Bodega ve todas las guías de prueba cargadas al arrancar.
        JsonNode guias = json(mockMvc.perform(get("/api/guias?estado=PENDIENTE").header("Authorization", jefe))
                .andExpect(status().isOk()).andReturn());
        long id = guias.get(0).get("id").asLong();

        // El jefe no aparece entre los que pueden recibir guías.
        mockMvc.perform(get("/api/repartidores?activos=true").header("Authorization", jefe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'jefe@calimport.local')]").isEmpty());

        // Un repartidor no puede asignarse guías.
        mockMvc.perform(patch("/api/guias/" + id + "/repartidor").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repartidorId\": 1}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/guias/" + id + "/repartidor").header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repartidorId\": 1}"))
                .andExpect(status().isOk());

        // El repartidor ve la suya, y no ve ni toca las de otro.
        mockMvc.perform(get("/api/guias").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.repartidorId != 1)]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isNotEmpty());
        mockMvc.perform(get("/api/guias").header("Authorization", otro))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        mockMvc.perform(get("/api/guias?repartidorId=1").header("Authorization", otro))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/guias/" + id + "/recepcion").header("Authorization", otro))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/guias/" + id + "/recepcion").header("Authorization", token))
                .andExpect(jsonPath("$.recibidaPorRepartidor").value(true));

        // Otro repartidor no puede subir evidencia a una guía ajena.
        mockMvc.perform(multipart("/api/guias/" + id + "/foto")
                        .file(new MockMultipartFile("archivo", "guia.jpg", "image/jpeg", JPEG))
                        .header("Authorization", otro))
                .andExpect(status().isForbidden());

        JsonNode foto = json(mockMvc.perform(multipart("/api/guias/" + id + "/foto")
                        .file(new MockMultipartFile("archivo", "guia.jpg", "image/jpeg", JPEG))
                        .header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn());

        mockMvc.perform(post("/api/guias/" + id + "/entrega").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(foto.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADA"))
                .andExpect(jsonPath("$.urlFoto").value(foto.get("urlFoto").asString()))
                .andExpect(jsonPath("$.hashFoto").value(foto.get("hashFoto").asString()));

        mockMvc.perform(get(foto.get("urlFoto").asString()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(content().bytes(JPEG));

        // Bodega ve el resultado y la evidencia.
        mockMvc.perform(get("/api/guias/" + id).header("Authorization", jefe))
                .andExpect(jsonPath("$.estado").value("ENTREGADA"));
        mockMvc.perform(get(foto.get("urlFoto").asString()).header("Authorization", jefe))
                .andExpect(status().isOk());

        // Ya resuelta: no admite otra foto.
        mockMvc.perform(multipart("/api/guias/" + id + "/foto")
                        .file(new MockMultipartFile("archivo", "otra.jpg", "image/jpeg", JPEG))
                        .header("Authorization", token))
                .andExpect(status().isConflict());
    }

    @Test
    void unRepartidorNoPuedeSincronizarDesdeSap() throws Exception {
        String token = login("dos@calimport.local", "1234");

        mockMvc.perform(post("/api/guias/sincronizar").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void elLoginDelJefeEmiteUnTokenConSuRol() throws Exception {
        String token = login("jefe@calimport.local", "1234").substring("Bearer ".length());
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertEquals("JEFE_BODEGA", mapper.readTree(payload).get("role").asString());
    }

    @Test
    void unJsonMalFormadoEsBadRequestYNo500() throws Exception {
        // Caso real: PowerShell 5.1 le quita las comillas al JSON que se le pasa a curl.exe.
        String token = login("uno@calimport.local", "1234");

        mockMvc.perform(post("/api/guias/1/entrega").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{urlFoto:/api/fotos/x.jpg}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lasFotosExigenToken() throws Exception {
        mockMvc.perform(get("/api/fotos/guia-1-00000000-0000-0000-0000-000000000000.jpg"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void subirSinElCampoArchivoEsBadRequest() throws Exception {
        String token = login("dos@calimport.local", "1234");

        mockMvc.perform(multipart("/api/guias/1/foto")
                        .file(new MockMultipartFile("otroCampo", "x.jpg", "image/jpeg", JPEG))
                        .header("Authorization", token))
                .andExpect(status().isBadRequest());
    }
}
