package com.calimport.guias.controller;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.calimport.guias.controller.dto.GenerarRutaRequest;
import com.calimport.guias.controller.dto.RutaResponse;
import com.calimport.guias.model.Rol;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.service.RutaService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RutaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RutaService rutaService;

    private String repartidor;
    private String jefe;

    @BeforeEach
    void setUp() {
        repartidor = "Bearer " + jwtTokenProvider.generateToken("juan@calimport.cl", 7, "Juan Perez", Rol.REPARTIDOR);
        jefe = "Bearer " + jwtTokenProvider.generateToken("jefe@calimport.cl", 9, "Jefe Bodega", Rol.JEFE_BODEGA);
    }

    private static RutaResponse ruta() {
        return new RutaResponse(1L, 7, LocalDate.of(2026, 9, 15), LocalTime.of(9, 30), List.of(),
                List.of("https://www.google.com/maps/dir/?api=1"), List.of());
    }

    @Test
    void sinTokenNoSeGeneraRuta() throws Exception {
        mockMvc.perform(post("/api/rutas").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        verify(rutaService, never()).generar(any());
    }

    @Test
    void generarRecibeHorasYFechasEnFormatoIso() throws Exception {
        when(rutaService.generar(any(GenerarRutaRequest.class))).thenReturn(ruta());

        mockMvc.perform(post("/api/rutas")
                        .header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repartidorId": 7, "fecha": "2026-09-15", "horaSalida": "09:30",
                                 "guias": [{"guiaId": 1, "ventanaDesde": "09:00", "ventanaHasta": "13:00"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horaSalida").value("09:30:00"));

        verify(rutaService).generar(new GenerarRutaRequest(7, LocalDate.of(2026, 9, 15), LocalTime.of(9, 30),
                List.of(new GenerarRutaRequest.GuiaEnRuta(1L, LocalTime.of(9, 0), LocalTime.of(13, 0), null))));
    }

    @Test
    void generarSinGuiasEsBadRequest() throws Exception {
        mockMvc.perform(post("/api/rutas")
                        .header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repartidorId": 7, "fecha": "2026-09-15", "horaSalida": "09:30", "guias": []}
                                """))
                .andExpect(status().isBadRequest());

        verify(rutaService, never()).generar(any());
    }

    @Test
    void unRepartidorNoPuedeGenerarRutas() throws Exception {
        mockMvc.perform(post("/api/rutas")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repartidorId": 7, "fecha": "2026-09-15", "horaSalida": "09:30",
                                 "guias": [{"guiaId": 1}]}
                                """))
                .andExpect(status().isForbidden());

        verify(rutaService, never()).generar(any());
    }

    @Test
    void unRepartidorNoConsultaLaRutaDeOtroPorId() throws Exception {
        mockMvc.perform(get("/api/rutas").param("repartidorId", "8").param("fecha", "2026-09-15")
                        .header("Authorization", repartidor))
                .andExpect(status().isForbidden());

        verify(rutaService, never()).obtener(anyInt(), any());
    }

    @Test
    void elJefeDeBodegaConsultaLaRutaDeCualquierRepartidor() throws Exception {
        when(rutaService.obtener(8, LocalDate.of(2026, 9, 15))).thenReturn(ruta());

        mockMvc.perform(get("/api/rutas").param("repartidorId", "8").param("fecha", "2026-09-15")
                        .header("Authorization", jefe))
                .andExpect(status().isOk());
    }

    @Test
    void miaUsaElRepartidorDelToken() throws Exception {
        when(rutaService.obtener(eq(7), eq(LocalDate.of(2026, 9, 15)))).thenReturn(ruta());

        mockMvc.perform(get("/api/rutas/mia").param("fecha", "2026-09-15").header("Authorization", repartidor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repartidorId").value(7));
    }
}
