package com.calimport.guias.controller;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.calimport.guias.model.Repartidor;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.service.RepartidorService;
import com.calimport.guias.utils.ApiException;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RepartidorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private RepartidorService repartidorService;

    private String bearer;

    @BeforeEach
    void setUp() {
        bearer = "Bearer " + jwtTokenProvider.generateToken("juan@calimport.cl", 7, "Juan Perez");
    }

    private Repartidor repartidor() {
        Repartidor repartidor = new Repartidor();
        repartidor.setEmployeeId(7);
        repartidor.setNombre("Juan Perez");
        repartidor.setEmail("juan@calimport.cl");
        repartidor.setActivo(true);
        return repartidor;
    }

    @Test
    void sinTokenNoSePuedeListarRepartidores() throws Exception {
        mockMvc.perform(get("/api/repartidores"))
                .andExpect(status().isUnauthorized());

        verify(repartidorService, never()).listar();
    }

    @Test
    void conTokenValidoSeListanTodosLosRepartidores() throws Exception {
        when(repartidorService.listar()).thenReturn(List.of(repartidor()));

        mockMvc.perform(get("/api/repartidores").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeId").value(7))
                .andExpect(jsonPath("$[0].nombre").value("Juan Perez"));
    }

    @Test
    void elFiltroDeActivosUsaLaConsultaDeActivos() throws Exception {
        when(repartidorService.listarActivos()).thenReturn(List.of(repartidor()));

        mockMvc.perform(get("/api/repartidores").param("activos", "true").header("Authorization", bearer))
                .andExpect(status().isOk());

        verify(repartidorService).listarActivos();
        verify(repartidorService, never()).listar();
    }

    @Test
    void activosFalseTraeLaListaCompleta() throws Exception {
        when(repartidorService.listar()).thenReturn(List.of(repartidor()));

        mockMvc.perform(get("/api/repartidores").param("activos", "false").header("Authorization", bearer))
                .andExpect(status().isOk());

        verify(repartidorService).listar();
        verify(repartidorService, never()).listarActivos();
    }

    @Test
    void unRepartidorInexistenteDevuelve404() throws Exception {
        when(repartidorService.obtenerPorId(99))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "No existe un repartidor con id 99"));

        mockMvc.perform(get("/api/repartidores/99").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe un repartidor con id 99"));
    }
}
