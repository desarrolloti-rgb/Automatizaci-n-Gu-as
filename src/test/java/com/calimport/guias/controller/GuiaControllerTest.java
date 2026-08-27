package com.calimport.guias.controller;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.service.GuiaService;
import com.calimport.guias.utils.ApiException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recorre la cadena real (filtro JWT, SecurityConfig, GlobalExceptionHandler): solo el
 * service está mockeado, para poder provocar cada rama sin depender de la BD.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GuiaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private GuiaService guiaService;

    private String bearer;

    @BeforeEach
    void setUp() {
        bearer = "Bearer " + jwtTokenProvider.generateToken("juan@calimport.cl", 7, "Juan Perez");
    }

    private Guia guia() {
        Guia guia = new Guia(1001, 5555L, "Cliente X", "Av. Siempre Viva 742");
        guia.setId(1L);
        return guia;
    }

    // --- seguridad ---

    @Test
    void sinTokenNoSePuedeListarGuias() throws Exception {
        mockMvc.perform(get("/api/guias"))
                .andExpect(status().isUnauthorized());

        verify(guiaService, never()).listar();
    }

    @Test
    void conUnTokenInvalidoTampocoSePuedeListarGuias() throws Exception {
        mockMvc.perform(get("/api/guias").header("Authorization", "Bearer token-falso"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conTokenValidoSePuedeListarGuias() throws Exception {
        when(guiaService.listar()).thenReturn(List.of(guia()));

        mockMvc.perform(get("/api/guias").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].docEntry").value(1001))
                .andExpect(jsonPath("$[0].folio").value(5555))
                .andExpect(jsonPath("$[0].estado").value("PENDIENTE"));
    }

    // --- filtros de listado ---

    @Test
    void elFiltroPorEstadoUsaLaConsultaPorEstado() throws Exception {
        when(guiaService.listarPorEstado(EstadoGuia.PENDIENTE)).thenReturn(List.of(guia()));

        mockMvc.perform(get("/api/guias").param("estado", "PENDIENTE").header("Authorization", bearer))
                .andExpect(status().isOk());

        verify(guiaService).listarPorEstado(EstadoGuia.PENDIENTE);
        verify(guiaService, never()).listar();
    }

    @Test
    void elFiltroPorRepartidorUsaLaConsultaPorRepartidor() throws Exception {
        when(guiaService.listarPorRepartidor(7)).thenReturn(List.of(guia()));

        mockMvc.perform(get("/api/guias").param("repartidorId", "7").header("Authorization", bearer))
                .andExpect(status().isOk());

        verify(guiaService).listarPorRepartidor(7);
        verify(guiaService, never()).listar();
    }

    // --- obtener / crear ---

    @Test
    void unaGuiaInexistenteDevuelve404YNoUn500() throws Exception {
        when(guiaService.obtenerPorId(99L))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "No existe una guía con id 99"));

        mockMvc.perform(get("/api/guias/99").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe una guía con id 99"));
    }

    @Test
    void crearUnaGuiaDevuelve201() throws Exception {
        when(guiaService.crear(1001, 5555L, "Cliente X", "Av. Siempre Viva 742")).thenReturn(guia());

        mockMvc.perform(post("/api/guias")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"docEntry":1001,"folio":5555,"cliente":"Cliente X","direccion":"Av. Siempre Viva 742"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docEntry").value(1001));
    }

    @Test
    void crearUnaGuiaSinClienteDevuelve400ConElCampoQueFallo() throws Exception {
        mockMvc.perform(post("/api/guias")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"docEntry":1001,"folio":5555,"cliente":"","direccion":"Av. Siempre Viva 742"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cliente")));

        verify(guiaService, never()).crear(anyInt(), any(), any(), any());
    }

    @Test
    void crearUnaGuiaDuplicadaDevuelve409() throws Exception {
        when(guiaService.crear(1001, 5555L, "Cliente X", "Av. Siempre Viva 742"))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "Ya existe una guía para el docEntry 1001"));

        mockMvc.perform(post("/api/guias")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"docEntry":1001,"folio":5555,"cliente":"Cliente X","direccion":"Av. Siempre Viva 742"}
                                """))
                .andExpect(status().isConflict());
    }

    // --- transiciones del ciclo de vida ---

    @Test
    void asignarRepartidorPasaElIdDelCuerpoAlService() throws Exception {
        when(guiaService.asignarRepartidor(1L, 7)).thenReturn(guia());

        mockMvc.perform(patch("/api/guias/1/repartidor")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repartidorId\":7}"))
                .andExpect(status().isOk());

        verify(guiaService).asignarRepartidor(1L, 7);
    }

    @Test
    void marcarRecepcionNoNecesitaCuerpo() throws Exception {
        when(guiaService.marcarRecibidaPorRepartidor(1L)).thenReturn(guia());

        mockMvc.perform(patch("/api/guias/1/recepcion").header("Authorization", bearer))
                .andExpect(status().isOk());

        verify(guiaService).marcarRecibidaPorRepartidor(1L);
    }

    @Test
    void entregarPasaLaEvidenciaFotograficaAlService() throws Exception {
        when(guiaService.entregar(eq(1L), any(), any())).thenReturn(guia());

        mockMvc.perform(post("/api/guias/1/entrega")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urlFoto\":\"https://fotos/1.jpg\",\"hashFoto\":\"abc123\"}"))
                .andExpect(status().isOk());

        verify(guiaService).entregar(1L, "https://fotos/1.jpg", "abc123");
    }

    @Test
    void entregarSinFotoDevuelve400() throws Exception {
        when(guiaService.entregar(eq(1L), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "urlFoto es obligatoria para entregar la guía"));

        mockMvc.perform(post("/api/guias/1/entrega")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hashFoto\":\"abc123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rechazarDevuelve200() throws Exception {
        when(guiaService.rechazar(1L)).thenReturn(guia());

        mockMvc.perform(post("/api/guias/1/rechazo").header("Authorization", bearer))
                .andExpect(status().isOk());

        verify(guiaService).rechazar(1L);
    }

    @Test
    void marcarSincronizadaDevuelve200() throws Exception {
        when(guiaService.marcarSincronizada(1L)).thenReturn(guia());

        mockMvc.perform(patch("/api/guias/1/sincronizada").header("Authorization", bearer))
                .andExpect(status().isOk());

        verify(guiaService).marcarSincronizada(1L);
    }

    // --- errores inesperados ---

    @Test
    void unErrorInternoNoFiltraDetallesAlCliente() throws Exception {
        when(guiaService.obtenerPorId(1L)).thenThrow(new IllegalStateException("password=secreto en el stacktrace"));

        mockMvc.perform(get("/api/guias/1").header("Authorization", bearer))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Error interno del servidor"));
    }
}
