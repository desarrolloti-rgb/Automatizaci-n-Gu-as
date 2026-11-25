package com.calimport.guias.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.calimport.guias.model.EnvioExterno;
import com.calimport.guias.model.ResultadoEnvio;
import com.calimport.guias.model.Rol;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.service.EnviosExternosService;
import com.calimport.guias.service.EnviosExternosService.SeguimientoVista;
import com.calimport.guias.service.EnviosExternosService.VistaBodega;
import com.calimport.guias.service.GuiaService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lo que bodega ve y hace de las guías Samex. Todo es del jefe de bodega. */
@SpringBootTest
@AutoConfigureMockMvc
class EnvioExternoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private EnviosExternosService envios;

    @MockitoBean
    private GuiaService guiaService;

    private String jefe;
    private String repartidor;

    @BeforeEach
    void setUp() {
        jefe = "Bearer " + jwtTokenProvider.generateToken("jefe@calimport.cl", 9, "Jefe Bodega", Rol.JEFE_BODEGA);
        repartidor = "Bearer " + jwtTokenProvider.generateToken("juan@calimport.cl", 7, "Juan Perez", Rol.Despachador);
    }

    @Test
    void bodegaVeElSeguimientoDeLasGuiasSamex() throws Exception {
        Instant hace = Instant.parse("2026-11-23T13:00:00Z");
        when(envios.vistaBodega(eq("SAMEX"), any())).thenReturn(new VistaBodega(hace, 1, List.of(
                new SeguimientoVista(1L, 311994L, "SAMEX", "999905761651", ResultadoEnvio.ASOCIADO, null,
                        "En reparto", null, false, false, hace, hace, false))));

        mockMvc.perform(get("/api/envios-externos").header("Authorization", jefe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.porBuscar").value(1))
                .andExpect(jsonPath("$.envios[0].numero").value("999905761651"))
                .andExpect(jsonPath("$.envios[0].resultado").value("ASOCIADO"))
                .andExpect(jsonPath("$.envios[0].revisar").value(false));
    }

    @Test
    void bodegaFijaElNumeroDeEnvioAMano() throws Exception {
        when(envios.fijarNumero(1L, "999905761651"))
                .thenReturn(new EnvioExterno(1L, "SAMEX", 311994L, Instant.now()));

        mockMvc.perform(patch("/api/guias/1/envio-externo")
                        .header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numero\":\"999905761651\"}"))
                .andExpect(status().isOk());

        verify(envios).fijarNumero(1L, "999905761651");
    }

    @Test
    void bodegaVeLosTransportistasQuePuedeElegir() throws Exception {
        when(guiaService.transportistas()).thenReturn(List.of("SAMEX"));

        mockMvc.perform(get("/api/transportistas").header("Authorization", jefe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("SAMEX"));
    }

    @Test
    void unRepartidorNoVeNiTocaNadaDeEsto() throws Exception {
        mockMvc.perform(get("/api/envios-externos").header("Authorization", repartidor))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/transportistas").header("Authorization", repartidor))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/guias/1/envio-externo")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numero\":\"999905761651\"}"))
                .andExpect(status().isForbidden());

        verify(envios, never()).vistaBodega(any(), any());
        verify(envios, never()).fijarNumero(anyLong(), any());
        verify(guiaService, never()).transportistas();
    }

    @Test
    void sinTokenEs401() throws Exception {
        mockMvc.perform(get("/api/envios-externos")).andExpect(status().isUnauthorized());
    }
}
