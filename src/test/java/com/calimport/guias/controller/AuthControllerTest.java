package com.calimport.guias.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.calimport.guias.controller.dto.LoginRequest;
import com.calimport.guias.controller.dto.LoginResponse;
import com.calimport.guias.sap.SapClient;
import com.calimport.guias.sap.SapSessionManager.SapUnauthorizedException;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.service.RepartidorService;
import com.calimport.guias.utils.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private static final String EMAIL = "juan@calimport.cl";
    private static final int EMPLOYEE_ID = 7;

    @Mock
    private SapClient sapClient;
    @Mock
    private RepartidorService repartidorService;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private PasswordEncoder passwordEncoder;
    private AuthController controller;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        controller = new AuthController(sapClient, repartidorService, passwordEncoder, jwtTokenProvider);
        mapper = new ObjectMapper();
    }

    /** Empleado tal como lo devuelve EmployeesInfo del Service Layer. */
    private ObjectNode empleado(String password) {
        ObjectNode empleado = mapper.createObjectNode();
        empleado.put("EmployeeID", EMPLOYEE_ID);
        empleado.put("eMail", EMAIL);
        empleado.put("FirstName", "Juan");
        empleado.put("LastName", "Perez");
        empleado.put("U_Password", password);
        return empleado;
    }

    private ObjectNode respuestaCon(ObjectNode... empleados) {
        ArrayNode arr = mapper.createArrayNode();
        for (ObjectNode empleado : empleados) {
            arr.add(empleado);
        }
        ObjectNode respuesta = mapper.createObjectNode();
        respuesta.set("value", arr);
        return respuesta;
    }

    // --- login correcto ---

    @Test
    void loginConPasswordBcryptDevuelveElTokenYSincronizaAlRepartidor() {
        when(sapClient.queryEmployeeByEmail(EMAIL))
                .thenReturn(respuestaCon(empleado(passwordEncoder.encode("secreta123"))));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez")).thenReturn("jwt-ok");

        LoginResponse respuesta = controller.login(new LoginRequest(EMAIL, "secreta123"));

        assertEquals("jwt-ok", respuesta.token());
        // El login es el único momento en que se refresca la copia local desde SAP.
        verify(repartidorService).upsertDesdeSap(EMPLOYEE_ID, "Juan Perez", EMAIL, true);
    }

    @Test
    void loginConPasswordEnTextoPlanoTambienFunciona() {
        // Los usuarios aún no migrados a BCrypt tienen U_Password en texto plano.
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("miPassword123")));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez")).thenReturn("jwt-plano");

        LoginResponse respuesta = controller.login(new LoginRequest(EMAIL, "miPassword123"));

        assertEquals("jwt-plano", respuesta.token());
    }

    @Test
    void elNombreSeArmaConFirstNameYLastName() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("pass")));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez")).thenReturn("jwt");

        controller.login(new LoginRequest(EMAIL, "pass"));

        verify(jwtTokenProvider).generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez");
    }

    @Test
    void unEmpleadoSinNombreEnSapNoRompeElLogin() {
        ObjectNode sinNombre = mapper.createObjectNode();
        sinNombre.put("EmployeeID", EMPLOYEE_ID);
        sinNombre.put("eMail", EMAIL);
        sinNombre.put("U_Password", "pass");

        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(sinNombre));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "")).thenReturn("jwt-sin-nombre");

        assertEquals("jwt-sin-nombre", controller.login(new LoginRequest(EMAIL, "pass")).token());
    }

    // --- credenciales inválidas ---

    @Test
    void passwordBcryptIncorrectaDevuelveUnauthorized() {
        when(sapClient.queryEmployeeByEmail(EMAIL))
                .thenReturn(respuestaCon(empleado(passwordEncoder.encode("la-correcta"))));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "la-incorrecta")));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        // Un login fallido no debe tocar la copia local ni emitir token.
        verifyNoInteractions(repartidorService);
        verify(jwtTokenProvider, never()).generateToken(anyString(), anyInt(), anyString());
    }

    @Test
    void passwordEnTextoPlanoIncorrectaDevuelveUnauthorized() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("la-correcta")));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "la-incorrecta")));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void unEmpleadoSinPasswordCargadaEnSapNoPuedeEntrar() {
        ObjectNode sinPassword = mapper.createObjectNode();
        sinPassword.put("EmployeeID", EMPLOYEE_ID);
        sinPassword.put("eMail", EMAIL);

        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(sinPassword));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "cualquiera")));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void passwordVaciaEnSapNoHabilitaEntrarConPasswordVacia() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("")));

        ApiException e = assertThrows(ApiException.class, () -> controller.login(new LoginRequest(EMAIL, "")));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    // --- usuario inexistente ---

    @Test
    void emailQueNoExisteEnSapDevuelveNotFound() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon());

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "cualquiera")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void respuestaDeSapSinArregloValueDevuelveNotFound() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(mapper.createObjectNode());

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "cualquiera")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    // --- fallas de SAP ---

    @Test
    void sesionCaidaConSapDevuelveBadGateway() {
        when(sapClient.queryEmployeeByEmail(anyString()))
                .thenThrow(new SapUnauthorizedException("sesion expirada"));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "pass")));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
    }

    @Test
    void sapCaidoDevuelveBadGatewayYNoUnErrorGenerico() {
        when(sapClient.queryEmployeeByEmail(anyString())).thenThrow(new RuntimeException("connection timeout"));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "pass")));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
    }

    @Test
    void respuestaDeSapSinEmployeeIdDevuelveBadGateway() {
        // Sin EmployeeID no hay identidad con que crear al repartidor: es un error de SAP,
        // no del usuario, así que no puede salir como 401 ni 404.
        ObjectNode sinId = mapper.createObjectNode();
        sinId.put("eMail", EMAIL);
        sinId.put("U_Password", "pass");

        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(sinId));

        ApiException e = assertThrows(ApiException.class, () -> controller.login(new LoginRequest(EMAIL, "pass")));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
        verify(repartidorService, never()).upsertDesdeSap(anyInt(), any(), any(), eq(true));
    }
}
