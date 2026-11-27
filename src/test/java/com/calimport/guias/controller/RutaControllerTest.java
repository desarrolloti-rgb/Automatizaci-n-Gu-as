package com.calimport.guias.controller;

import java.time.LocalDate;
import java.time.LocalTime;
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

import com.calimport.guias.controller.dto.GenerarRutaRequest;
import com.calimport.guias.controller.dto.OdometroRequest;
import com.calimport.guias.controller.dto.RutaResponse;
import com.calimport.guias.controller.dto.RutaResumen;
import com.calimport.guias.model.EstadoRuta;
import com.calimport.guias.model.Rol;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.service.RutaService;
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
        repartidor = "Bearer " + jwtTokenProvider.generateToken("juan@calimport.cl", 7, "Juan Perez", Rol.Despachador);
        jefe = "Bearer " + jwtTokenProvider.generateToken("jefe@calimport.cl", 9, "Jefe Bodega", Rol.JEFE_BODEGA);
    }

    private static RutaResponse ruta() {
        return new RutaResponse(1L, 7, LocalDate.of(2026, 9, 15), LocalTime.of(9, 30), true, 23.5, 220,
                LocalTime.of(13, 10), 12340, LocalTime.of(9, 25), null, null,
                List.of(), List.of("https://www.google.com/maps/dir/?api=1"), List.of(), EstadoRuta.EN_CURSO);
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
                .andExpect(jsonPath("$.horaSalida").value("09:30:00"))
                .andExpect(jsonPath("$.vuelveABodega").value(true))
                .andExpect(jsonPath("$.kmPlanificados").value(23.5))
                .andExpect(jsonPath("$.minutosPlanificados").value(220))
                .andExpect(jsonPath("$.terminoEstimado").value("13:10:00"));

        // Sin "vuelveABodega" llega null: el servicio usa lo habitual del repartidor.
        verify(rutaService).generar(new GenerarRutaRequest(7, LocalDate.of(2026, 9, 15), LocalTime.of(9, 30),
                List.of(new GenerarRutaRequest.GuiaEnRuta(1L, LocalTime.of(9, 0), LocalTime.of(13, 0), null)), null));
    }

    @Test
    void generarRecibeSiLaRutaVuelveABodega() throws Exception {
        when(rutaService.generar(any(GenerarRutaRequest.class))).thenReturn(ruta());

        mockMvc.perform(post("/api/rutas")
                        .header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repartidorId": 7, "fecha": "2026-09-15", "horaSalida": "09:30",
                                 "guias": [{"guiaId": 1}], "vuelveABodega": true}
                                """))
                .andExpect(status().isOk());

        verify(rutaService).generar(new GenerarRutaRequest(7, LocalDate.of(2026, 9, 15), LocalTime.of(9, 30),
                List.of(new GenerarRutaRequest.GuiaEnRuta(1L, null, null, null)), true));
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
                .andExpect(jsonPath("$.repartidorId").value(7))
                .andExpect(jsonPath("$.odometroSalida").value(12340))
                .andExpect(jsonPath("$.odometroSalidaHora").value("09:25:00"))
                .andExpect(jsonPath("$.odometroTermino").isEmpty());
    }

    @Test
    void elRepartidorAnotaElOdometroEnSuPropiaRuta() throws Exception {
        when(rutaService.anotarOdometro(eq(7), any(OdometroRequest.class))).thenReturn(ruta());

        mockMvc.perform(patch("/api/rutas/mia/odometro")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salida\": 12340}"))
                .andExpect(status().isOk());

        verify(rutaService).anotarOdometro(7, new OdometroRequest(12340, null));
    }

    @Test
    void elJefeDeBodegaNoAnotaElOdometro() throws Exception {
        mockMvc.perform(patch("/api/rutas/mia/odometro")
                        .header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salida\": 12340}"))
                .andExpect(status().isForbidden());

        verify(rutaService, never()).anotarOdometro(anyInt(), any());
    }

    @Test
    void unOdometroNegativoEsBadRequest() throws Exception {
        mockMvc.perform(patch("/api/rutas/mia/odometro")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termino\": -5}"))
                .andExpect(status().isBadRequest());

        verify(rutaService, never()).anotarOdometro(anyInt(), any());
    }

    // --- historial ---

    @Test
    void elJefeDeBodegaVeElHistorialDeRutas() throws Exception {
        LocalDate desde = LocalDate.of(2026, 9, 1);
        LocalDate hasta = LocalDate.of(2026, 9, 15);
        when(rutaService.historial(desde, hasta, null)).thenReturn(List.of(new RutaResumen(1L, 7, "Juan Perez",
                hasta, LocalTime.of(9, 30), EstadoRuta.COMPLETADA, true, 5, 4, 1, 0, 23.5, 12340, 12371, 31)));

        mockMvc.perform(get("/api/rutas/historial").header("Authorization", jefe)
                        .param("desde", "2026-09-01").param("hasta", "2026-09-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].repartidor").value("Juan Perez"))
                .andExpect(jsonPath("$[0].estado").value("COMPLETADA"))
                .andExpect(jsonPath("$[0].entregadas").value(4))
                .andExpect(jsonPath("$[0].kmRecorridos").value(31))
                .andExpect(jsonPath("$[0].fecha").value("2026-09-15"));
    }

    @Test
    void elHistorialFiltraPorRepartidor() throws Exception {
        when(rutaService.historial(any(), any(), eq(7))).thenReturn(List.of());

        mockMvc.perform(get("/api/rutas/historial").header("Authorization", jefe)
                        .param("desde", "2026-09-01").param("hasta", "2026-09-15").param("repartidorId", "7"))
                .andExpect(status().isOk());

        verify(rutaService).historial(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), 7);
    }

    @Test
    void unRepartidorNoVeElHistorial() throws Exception {
        mockMvc.perform(get("/api/rutas/historial").header("Authorization", repartidor)
                        .param("desde", "2026-09-01").param("hasta", "2026-09-15"))
                .andExpect(status().isForbidden());

        verify(rutaService, never()).historial(any(), any(), any());
    }

    @Test
    void elHistorialSinFechasEsBadRequest() throws Exception {
        // La validación es del servicio (RutaServiceTest); acá, que su 400 llega tal cual.
        when(rutaService.historial(null, null, null))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "Indica desde y hasta qué fecha"));

        mockMvc.perform(get("/api/rutas/historial").header("Authorization", jefe))
                .andExpect(status().isBadRequest());
    }
}
