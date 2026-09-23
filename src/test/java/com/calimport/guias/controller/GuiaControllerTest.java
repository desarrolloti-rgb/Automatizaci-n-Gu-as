package com.calimport.guias.controller;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.Rol;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.security.UsuarioActual;
import com.calimport.guias.service.GuiaService;
import com.calimport.guias.service.GuiaSyncService;
import com.calimport.guias.utils.ApiException;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recorre la cadena real (filtro JWT, SecurityConfig, @PreAuthorize, GlobalExceptionHandler):
 * solo el service está mockeado, para poder provocar cada rama sin depender de la BD.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GuiaControllerTest {

    private static final UsuarioActual REPARTIDOR = new UsuarioActual(7, Rol.Despachador);
    private static final UsuarioActual JEFE = new UsuarioActual(9, Rol.JEFE_BODEGA);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Value("${security.jwt.secret}")
    private String secret;

    @MockitoBean
    private GuiaService guiaService;

    @MockitoBean
    private GuiaSyncService guiaSyncService;

    private String repartidor;
    private String jefe;

    @BeforeEach
    void setUp() {
        repartidor = "Bearer " + jwtTokenProvider.generateToken("juan@calimport.cl", 7, "Juan Perez", Rol.Despachador);
        jefe = "Bearer " + jwtTokenProvider.generateToken("jefe@calimport.cl", 9, "Jefe Bodega", Rol.JEFE_BODEGA);
    }

    private Guia guia() {
        Guia guia = new Guia(1001, 5555L, "Cliente X", "Av. Siempre Viva 742");
        guia.setId(1L);
        return guia;
    }

    // --- autenticación ---

    @Test
    void sinTokenNoSePuedeListarGuias() throws Exception {
        mockMvc.perform(get("/api/guias"))
                .andExpect(status().isUnauthorized());

        verify(guiaService, never()).listarPara(any(), any(), any());
    }

    @Test
    void conUnTokenInvalidoTampocoSePuedeListarGuias() throws Exception {
        mockMvc.perform(get("/api/guias").header("Authorization", "Bearer token-falso"))
                .andExpect(status().isUnauthorized());
    }

    // --- listado: el service recibe quién pregunta, sacado del token ---

    @Test
    void elListadoLlegaAlServiceConElUsuarioDelToken() throws Exception {
        when(guiaService.listarPara(JEFE, null, null)).thenReturn(List.of(guia()));

        mockMvc.perform(get("/api/guias").header("Authorization", jefe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].docEntry").value(1001))
                .andExpect(jsonPath("$[0].folio").value(5555))
                .andExpect(jsonPath("$[0].estado").value("PENDIENTE"));
    }

    @Test
    void losFiltrosDelQueryLleganAlService() throws Exception {
        when(guiaService.listarPara(REPARTIDOR, EstadoGuia.PENDIENTE, 7)).thenReturn(List.of(guia()));

        mockMvc.perform(get("/api/guias").param("estado", "PENDIENTE").param("repartidorId", "7")
                        .header("Authorization", repartidor))
                .andExpect(status().isOk());

        verify(guiaService).listarPara(REPARTIDOR, EstadoGuia.PENDIENTE, 7);
    }

    @Test
    void unRepartidorQuePideLasGuiasDeOtroRecibe403() throws Exception {
        when(guiaService.listarPara(REPARTIDOR, null, 8))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "Solo puede consultar sus propias guías"));

        mockMvc.perform(get("/api/guias").param("repartidorId", "8").header("Authorization", repartidor))
                .andExpect(status().isForbidden());
    }

    @Test
    void unTokenSinClaimDeRolSeTrataComoRepartidor() throws Exception {
        // Tokens emitidos antes de que existiera el rol, o manipulados: nunca más permisos.
        String sinRol = "Bearer " + Jwts.builder()
                .subject("viejo@calimport.cl")
                .claim("employeeId", 7)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(post("/api/guias/sincronizar").header("Authorization", sinRol))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/guias").header("Authorization", sinRol))
                .andExpect(status().isOk());

        verify(guiaService).listarPara(REPARTIDOR, null, null);
    }

    // --- obtener / crear ---

    @Test
    void unaGuiaInexistenteDevuelve404YNoUn500() throws Exception {
        when(guiaService.obtenerPara(99L, JEFE))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "No existe una guía con id 99"));

        mockMvc.perform(get("/api/guias/99").header("Authorization", jefe))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe una guía con id 99"));
    }

    @Test
    void unRepartidorQuePideUnaGuiaAjenaRecibe403() throws Exception {
        when(guiaService.obtenerPara(1L, REPARTIDOR))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "La guía no está asignada a este repartidor"));

        mockMvc.perform(get("/api/guias/1").header("Authorization", repartidor))
                .andExpect(status().isForbidden());
    }

    @Test
    void crearUnaGuiaDevuelve201() throws Exception {
        when(guiaService.crear(1001, 5555L, "Cliente X", "Av. Siempre Viva 742")).thenReturn(guia());

        mockMvc.perform(post("/api/guias")
                        .header("Authorization", jefe)
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
                        .header("Authorization", jefe)
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
                        .header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"docEntry":1001,"folio":5555,"cliente":"Cliente X","direccion":"Av. Siempre Viva 742"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void unRepartidorNoPuedeCrearGuias() throws Exception {
        mockMvc.perform(post("/api/guias")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"docEntry":1001,"folio":5555,"cliente":"Cliente X","direccion":"Av. Siempre Viva 742"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("No tiene permisos para esta operación"));

        verify(guiaService, never()).crear(anyInt(), any(), any(), any());
    }

    // --- operaciones de bodega ---

    @Test
    void asignarRepartidorPasaElIdDelCuerpoAlService() throws Exception {
        when(guiaService.asignarRepartidor(1L, 7)).thenReturn(guia());

        mockMvc.perform(patch("/api/guias/1/repartidor")
                        .header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repartidorId\":7}"))
                .andExpect(status().isOk());

        verify(guiaService).asignarRepartidor(1L, 7);
    }

    @Test
    void unRepartidorNoPuedeAsignarGuias() throws Exception {
        mockMvc.perform(patch("/api/guias/1/repartidor")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repartidorId\":7}"))
                .andExpect(status().isForbidden());

        verify(guiaService, never()).asignarRepartidor(anyLong(), any());
    }

    @Test
    void unRepartidorNoPuedeDefinirHorarios() throws Exception {
        mockMvc.perform(patch("/api/guias/1/horario")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ventanaDesde\":\"09:00\",\"ventanaHasta\":\"13:00\"}"))
                .andExpect(status().isForbidden());

        verify(guiaService, never()).definirHorario(anyLong(), any(), any(), any());
    }

    // --- operaciones del repartidor: el id sale del token ---

    @Test
    void marcarRecepcionUsaElRepartidorDelToken() throws Exception {
        when(guiaService.marcarRecibidaPorRepartidor(1L, 7)).thenReturn(guia());

        mockMvc.perform(patch("/api/guias/1/recepcion").header("Authorization", repartidor))
                .andExpect(status().isOk());

        verify(guiaService).marcarRecibidaPorRepartidor(1L, 7);
    }

    @Test
    void entregarPasaLaEvidenciaFotograficaAlService() throws Exception {
        when(guiaService.entregar(eq(1L), eq(7), any(), any())).thenReturn(guia());

        mockMvc.perform(post("/api/guias/1/entrega")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urlFoto\":\"https://fotos/1.jpg\",\"hashFoto\":\"abc123\"}"))
                .andExpect(status().isOk());

        verify(guiaService).entregar(1L, 7, "https://fotos/1.jpg", "abc123");
    }

    @Test
    void entregarSinFotoDevuelve400() throws Exception {
        when(guiaService.entregar(eq(1L), eq(7), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "urlFoto es obligatoria para entregar la guía"));

        mockMvc.perform(post("/api/guias/1/entrega")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hashFoto\":\"abc123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void elJefeDeBodegaNoEntregaNiRechazaGuias() throws Exception {
        mockMvc.perform(post("/api/guias/1/entrega")
                        .header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"urlFoto\":\"https://fotos/1.jpg\",\"hashFoto\":\"abc123\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/guias/1/rechazo")
                        .header("Authorization", jefe)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"No estaba el encargado\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/guias/1/recepcion").header("Authorization", jefe))
                .andExpect(status().isForbidden());

        verify(guiaService, never()).entregar(anyLong(), anyInt(), any(), any());
        verify(guiaService, never()).rechazar(anyLong(), anyInt(), any());
        verify(guiaService, never()).marcarRecibidaPorRepartidor(anyLong(), anyInt());
    }

    @Test
    void rechazarPasaElMotivoAlService() throws Exception {
        when(guiaService.rechazar(1L, 7, "El cliente no tenía espacio")).thenReturn(guia());

        mockMvc.perform(post("/api/guias/1/rechazo")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"El cliente no tenía espacio\"}"))
                .andExpect(status().isOk());

        verify(guiaService).rechazar(1L, 7, "El cliente no tenía espacio");
    }

    @Test
    void rechazarSinMotivoEsBadRequestYNoLlegaAlService() throws Exception {
        mockMvc.perform(post("/api/guias/1/rechazo")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"  \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/guias/1/rechazo")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(guiaService, never()).rechazar(anyLong(), anyInt(), any());
    }

    @Test
    void rechazarUnaGuiaAjenaDevuelve403() throws Exception {
        when(guiaService.rechazar(1L, 7, "No estaba el encargado"))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "La guía no está asignada a este repartidor"));

        mockMvc.perform(post("/api/guias/1/rechazo")
                        .header("Authorization", repartidor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"No estaba el encargado\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reintentarSincronizacionEsDeBodegaYNoDelRepartidor() throws Exception {
        when(guiaService.reintentarSincronizacion(1L)).thenReturn(guia());

        mockMvc.perform(patch("/api/guias/1/sincronizada").header("Authorization", jefe))
                .andExpect(status().isOk());
        // Reenviar a SAP es operación de la integración, no parte del reparto.
        mockMvc.perform(patch("/api/guias/1/sincronizada").header("Authorization", repartidor))
                .andExpect(status().isForbidden());

        verify(guiaService, times(1)).reintentarSincronizacion(1L);
    }

    // --- reapertura (flujo R -> P) ---

    @Test
    void reabrirEsDeBodegaYNoDelRepartidor() throws Exception {
        when(guiaService.reabrir(1L)).thenReturn(guia());

        mockMvc.perform(patch("/api/guias/1/reapertura").header("Authorization", jefe))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/guias/1/reapertura").header("Authorization", repartidor))
                .andExpect(status().isForbidden());

        verify(guiaService, times(1)).reabrir(1L);
    }

    // --- sincronizacion desde SAP ---

    @Test
    void sincronizarSinTokenNoLlegaASap() throws Exception {
        mockMvc.perform(post("/api/guias/sincronizar"))
                .andExpect(status().isUnauthorized());

        verify(guiaSyncService, never()).sincronizar(any(), any());
    }

    @Test
    void unRepartidorNoPuedeSincronizar() throws Exception {
        mockMvc.perform(post("/api/guias/sincronizar").header("Authorization", repartidor))
                .andExpect(status().isForbidden());

        verify(guiaSyncService, never()).sincronizar(any(), any());
    }

    @Test
    void sincronizarDevuelveElResumenDeLoImportado() throws Exception {
        when(guiaSyncService.sincronizar(LocalDate.of(2026, 8, 1), null))
                .thenReturn(new GuiaSyncService.Resultado(12, 1));

        mockMvc.perform(post("/api/guias/sincronizar")
                        .param("desde", "2026-08-01")
                        .header("Authorization", jefe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sincronizadas").value(12))
                .andExpect(jsonPath("$.descartadas").value(1));
    }

    @Test
    void sincronizarSinFechaUsaHoy() throws Exception {
        when(guiaSyncService.sincronizar(LocalDate.now(), null))
                .thenReturn(new GuiaSyncService.Resultado(0, 0));

        mockMvc.perform(post("/api/guias/sincronizar").header("Authorization", jefe))
                .andExpect(status().isOk());

        verify(guiaSyncService).sincronizar(LocalDate.now(), null);
    }

    @Test
    void sincronizarAceptaUnRangoDeFechas() throws Exception {
        // Acotar por arriba sirve para reimportar un dia puntual sin arrastrar todo lo
        // emitido despues.
        when(guiaSyncService.sincronizar(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(new GuiaSyncService.Resultado(5, 0));

        mockMvc.perform(post("/api/guias/sincronizar")
                        .param("desde", "2026-08-01")
                        .param("hasta", "2026-08-31")
                        .header("Authorization", jefe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sincronizadas").value(5));
    }

    @Test
    void unRangoAlRevesEsBadRequestYNoLlegaASap() throws Exception {
        mockMvc.perform(post("/api/guias/sincronizar")
                        .param("desde", "2026-08-31")
                        .param("hasta", "2026-08-01")
                        .header("Authorization", jefe))
                .andExpect(status().isBadRequest());

        verify(guiaSyncService, never()).sincronizar(any(), any());
    }

    @Test
    void sincronizarConSapCaidoDevuelveBadGateway() throws Exception {
        when(guiaSyncService.sincronizar(any(), any()))
                .thenThrow(new RuntimeException("connection timeout"));

        mockMvc.perform(post("/api/guias/sincronizar").header("Authorization", jefe))
                .andExpect(status().isBadGateway());
    }

    // --- errores inesperados ---

    @Test
    void unErrorInternoNoFiltraDetallesAlCliente() throws Exception {
        when(guiaService.obtenerPara(1L, JEFE)).thenThrow(new IllegalStateException("password=secreto en el stacktrace"));

        mockMvc.perform(get("/api/guias/1").header("Authorization", jefe))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Error interno del servidor"));
    }
}
