package com.calimport.guias.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.calimport.guias.model.Rol;
import com.calimport.guias.sap.SapClient;
import com.calimport.guias.sap.SapSessionManager.SapUnauthorizedException;
import com.calimport.guias.security.JwtTokenProvider;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DeliveryNoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private SapClient sapClient;

    private String bearer;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        bearer = "Bearer " + jwtTokenProvider.generateToken("jefe@calimport.cl", 9, "Jefe Bodega", Rol.JEFE_BODEGA);
        mapper = new ObjectMapper();
    }

    private ObjectNode respuestaSap() {
        ObjectNode guia = mapper.createObjectNode();
        guia.put("DocEntry", 4521);
        guia.put("DocNum", 139147);
        guia.put("CardName", "Cliente X");
        guia.put("Address", "Av. Siempre Viva 742");

        ArrayNode arr = mapper.createArrayNode();
        arr.add(guia);

        ObjectNode respuesta = mapper.createObjectNode();
        respuesta.set("value", arr);
        return respuesta;
    }

    @Test
    void sinTokenNoSePuedeConsultarSap() throws Exception {
        mockMvc.perform(get("/api/sap/delivery-notes"))
                .andExpect(status().isUnauthorized());

        verify(sapClient, never()).fetchDeliveryNotes(anyInt());
    }

    @Test
    void devuelveElJsonDeSapSinTransformar() throws Exception {
        when(sapClient.fetchDeliveryNotes(1)).thenReturn(respuestaSap());

        mockMvc.perform(get("/api/sap/delivery-notes").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value[0].DocEntry").value(4521))
                .andExpect(jsonPath("$.value[0].CardName").value("Cliente X"));
    }

    @Test
    void sinParametroTraeUnSoloDocumento() throws Exception {
        when(sapClient.fetchDeliveryNotes(1)).thenReturn(respuestaSap());

        mockMvc.perform(get("/api/sap/delivery-notes").header("Authorization", bearer))
                .andExpect(status().isOk());

        verify(sapClient).fetchDeliveryNotes(1);
    }

    @Test
    void topPersonalizadoSePasaASap() throws Exception {
        when(sapClient.fetchDeliveryNotes(5)).thenReturn(respuestaSap());

        mockMvc.perform(get("/api/sap/delivery-notes").param("top", "5").header("Authorization", bearer))
                .andExpect(status().isOk());

        verify(sapClient).fetchDeliveryNotes(5);
    }

    @Test
    void unTopFueraDeRangoNoLlegaASap() throws Exception {
        // Un documento de SAP sin recortar es grande: sin tope se cuelga la sesion.
        mockMvc.perform(get("/api/sap/delivery-notes").param("top", "500").header("Authorization", bearer))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/sap/delivery-notes").param("top", "0").header("Authorization", bearer))
                .andExpect(status().isBadRequest());

        verify(sapClient, never()).fetchDeliveryNotes(anyInt());
    }

    @Test
    void sesionCaidaConSapDevuelveBadGateway() throws Exception {
        when(sapClient.fetchDeliveryNotes(1)).thenThrow(new SapUnauthorizedException("sesion expirada"));

        mockMvc.perform(get("/api/sap/delivery-notes").header("Authorization", bearer))
                .andExpect(status().isBadGateway());
    }

    @Test
    void sapCaidoDevuelveBadGatewayYNoUn500() throws Exception {
        when(sapClient.fetchDeliveryNotes(1)).thenThrow(new RuntimeException("connection timeout"));

        mockMvc.perform(get("/api/sap/delivery-notes").header("Authorization", bearer))
                .andExpect(status().isBadGateway());
    }
}
