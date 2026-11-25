package com.calimport.guias.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.calimport.guias.controller.dto.LoginRequest;
import com.calimport.guias.controller.dto.LoginResponse;
import com.calimport.guias.model.Rol;
import com.calimport.guias.sap.SapClient;
import com.calimport.guias.sap.SapSessionManager.SapUnauthorizedException;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.security.LimitadorLogin;
import com.calimport.guias.security.SesionComun;
import com.calimport.guias.service.RepartidorService;
import com.calimport.guias.utils.ApiException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private SesionComun sesionComun;
    private AuthController controller;
    private ObjectMapper mapper;
    private MockHttpServletRequest http;
    private MockHttpServletResponse salida;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        // 3 fallos por correo y 5 por IP, para no repetir 20 logins en cada prueba.
        LimitadorLogin limitador = new LimitadorLogin(3, 5, 5, 15);
        sesionComun = new SesionComun("secreto-compartido-solo-para-pruebas-0123456789", "calimport_sesion", 30, true);
        controller = new AuthController(sapClient, repartidorService, passwordEncoder, jwtTokenProvider, limitador,
                sesionComun);
        mapper = new ObjectMapper();
        http = new MockHttpServletRequest();
        http.setRemoteAddr("10.1.1.1");
        salida = new MockHttpServletResponse();
    }

    /** Empleado tal como lo devuelve EmployeesInfo del Service Layer. */
    private ObjectNode empleado(String password) {
        ObjectNode empleado = mapper.createObjectNode();
        empleado.put("EmployeeID", EMPLOYEE_ID);
        empleado.put("eMail", EMAIL);
        empleado.put("FirstName", "Juan");
        empleado.put("LastName", "Perez");
        empleado.put("JobTitle", "Bodeguero");
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
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez", Rol.Despachador)).thenReturn("jwt-ok");

        LoginResponse respuesta = controller.login(new LoginRequest(EMAIL, "secreta123"), http, salida);

        assertEquals("jwt-ok", respuesta.token());
        // El login es el único momento en que se refresca la copia local desde SAP.
        verify(repartidorService).upsertDesdeSap(EMPLOYEE_ID, "Juan Perez", EMAIL, true, Rol.Despachador);
    }

    @Test
    void unJobTitleQueEmpiezaConJefeEntraComoJefeDeBodega() {
        // El match es por prefijo: el typo real de SAP ("Jefe bogeda") y variantes como
        // "Jefe de bodega" entran igual, sin depender de una lista de emails.
        ObjectNode jefe = empleado("pass");
        jefe.put("eMail", "jefe@calimport.cl");
        jefe.put("JobTitle", "Jefe bogeda");
        when(sapClient.queryEmployeeByEmail("jefe@calimport.cl")).thenReturn(respuestaCon(jefe));
        when(jwtTokenProvider.generateToken("jefe@calimport.cl", EMPLOYEE_ID, "Juan Perez", Rol.JEFE_BODEGA))
                .thenReturn("jwt-jefe");

        assertEquals("jwt-jefe", controller.login(new LoginRequest("jefe@calimport.cl", "pass"), http, salida).token());
        verify(repartidorService).upsertDesdeSap(EMPLOYEE_ID, "Juan Perez", "jefe@calimport.cl", true, Rol.JEFE_BODEGA);
    }

    @Test
    void elJefeDeBodegaQuedaConLaSesionComunConElPanel() {
        ObjectNode jefe = empleado("pass");
        jefe.put("eMail", "jefe@calimport.cl");
        jefe.put("JobTitle", "Jefe Bodega");
        when(sapClient.queryEmployeeByEmail("jefe@calimport.cl")).thenReturn(respuestaCon(jefe));
        when(jwtTokenProvider.generateToken(anyString(), anyInt(), anyString(), any())).thenReturn("jwt-jefe");

        controller.login(new LoginRequest("jefe@calimport.cl", "pass"), http, salida);

        String cookie = salida.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie, "el jefe de bodega usa también el panel: entra a los dos con un solo ingreso");
        String valor = cookie.substring("calimport_sesion=".length(), cookie.indexOf(';'));
        SesionComun.Datos datos = sesionComun.verificar(valor).orElseThrow();
        assertEquals("jefe@calimport.cl", datos.email());
        assertEquals(EMPLOYEE_ID, datos.employeeId());
        assertTrue(sesionComun.selloCoincide(datos, "pass"), "el sello es de la clave guardada en SAP");
    }

    @Test
    void elDespachadorNoRecibeSesionComunYSeBorraLaQueHubieraDejadoElJefe() {
        // Decisión del 2026-11-09: la sesión larga es solo para el jefe de bodega. Y si el jefe
        // no salió en ese navegador, su cookie se borra: si no, al vencer el token del
        // despachador, el canje lo dejaba adentro como jefe.
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("pass")));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez", Rol.Despachador)).thenReturn("jwt");

        controller.login(new LoginRequest(EMAIL, "pass"), http, salida);

        String cookie = salida.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("calimport_sesion=;") && cookie.contains("Max-Age=0") && cookie.contains("Path=/"),
                cookie);
    }

    @Test
    void conLaSesionComunApagadaElLoginNoTocaLaCookie() {
        // Instancia de prueba: la cookie que ve es la de producción y no le corresponde borrarla.
        AuthController sinSesionComun = new AuthController(sapClient, repartidorService, passwordEncoder,
                jwtTokenProvider, new LimitadorLogin(3, 5, 5, 15), new SesionComun("", "calimport_sesion", 30, true));
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("pass")));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez", Rol.Despachador)).thenReturn("jwt");

        sinSesionComun.login(new LoginRequest(EMAIL, "pass"), http, salida);

        assertNull(salida.getHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void unBodegueroDeSucursalEntraComoDespachador() {
        ObjectNode despachador = empleado("pass");
        despachador.put("JobTitle", "Bodeguero CCP");
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(despachador));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez", Rol.Despachador))
                .thenReturn("jwt-bodega");

        assertEquals("jwt-bodega", controller.login(new LoginRequest(EMAIL, "pass"), http, salida).token());
        verify(repartidorService).upsertDesdeSap(EMPLOYEE_ID, "Juan Perez", EMAIL, true, Rol.Despachador);
    }

    @Test
    void unCargoQueNoEsDeBodegaNoEntraAunqueLaClaveSeaCorrecta() {
        // Cada cargo entra a una sola app: Facturador va al Panel de Facturadores.
        ObjectNode facturador = empleado("pass");
        facturador.put("JobTitle", "Facturador");
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(facturador));

        ApiException e = assertThrows(ApiException.class, () -> controller.login(new LoginRequest(EMAIL, "pass"), http, salida));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        verifyNoInteractions(repartidorService);
        verify(jwtTokenProvider, never()).generateToken(anyString(), anyInt(), anyString(), any());
    }

    @Test
    void unEmpleadoSinCargoNoEntra() {
        ObjectNode sinCargo = empleado("pass");
        sinCargo.remove("JobTitle");
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(sinCargo));

        ApiException e = assertThrows(ApiException.class, () -> controller.login(new LoginRequest(EMAIL, "pass"), http, salida));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    @Test
    void losCargosRealesDeSapSeReparten() {
        // Cargos tal como estaban en OHEM el 2026-10-06.
        assertEquals(Optional.of(Rol.JEFE_BODEGA), AuthController.rolDeCargo("Jefe Bodega"));
        assertEquals(Optional.of(Rol.JEFE_BODEGA), AuthController.rolDeCargo("  jefe   bogeda "));
        for (String bodega : List.of("Bodeguero", "Bodeguero CCP", "Bodeguera ANT", "Bodeguero PMO", "Bodega",
                "Personal bodega", "PERSONAL BODEGA", "Despachador", "despachadora")) {
            assertEquals(Optional.of(Rol.Despachador), AuthController.rolDeCargo(bodega), bodega);
        }
        for (String otro : List.of("Facturador", "Logistica", "STOCK", "Ventas Terreno", "Gerencia Operaciones",
                "Jefe de Ventas", "Soporte TI", "")) {
            assertEquals(Optional.empty(), AuthController.rolDeCargo(otro), otro);
        }
        assertEquals(Optional.empty(), AuthController.rolDeCargo(null));
    }

    @Test
    void loginConPasswordEnTextoPlanoTambienFunciona() {
        // Los usuarios aún no migrados a BCrypt tienen U_Password en texto plano.
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("miPassword123")));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez", Rol.Despachador)).thenReturn("jwt-plano");

        LoginResponse respuesta = controller.login(new LoginRequest(EMAIL, "miPassword123"), http, salida);

        assertEquals("jwt-plano", respuesta.token());
    }

    @Test
    void elNombreSeArmaConFirstNameYLastName() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("pass")));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez", Rol.Despachador)).thenReturn("jwt");

        controller.login(new LoginRequest(EMAIL, "pass"), http, salida);

        verify(jwtTokenProvider).generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez", Rol.Despachador);
    }

    @Test
    void unEmpleadoSinNombreEnSapNoRompeElLogin() {
        ObjectNode sinNombre = mapper.createObjectNode();
        sinNombre.put("EmployeeID", EMPLOYEE_ID);
        sinNombre.put("eMail", EMAIL);
        sinNombre.put("JobTitle", "Bodeguero");
        sinNombre.put("U_Password", "pass");

        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(sinNombre));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "", Rol.Despachador)).thenReturn("jwt-sin-nombre");

        assertEquals("jwt-sin-nombre", controller.login(new LoginRequest(EMAIL, "pass"), http, salida).token());
    }

    // --- credenciales inválidas ---

    @Test
    void passwordBcryptIncorrectaDevuelveUnauthorized() {
        when(sapClient.queryEmployeeByEmail(EMAIL))
                .thenReturn(respuestaCon(empleado(passwordEncoder.encode("la-correcta"))));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "la-incorrecta"), http, salida));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        // Un login fallido no debe tocar la copia local ni emitir token.
        verifyNoInteractions(repartidorService);
        verify(jwtTokenProvider, never()).generateToken(anyString(), anyInt(), anyString(), any());
    }

    @Test
    void passwordEnTextoPlanoIncorrectaDevuelveUnauthorized() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("la-correcta")));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "la-incorrecta"), http, salida));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void unEmpleadoSinPasswordCargadaEnSapNoPuedeEntrar() {
        ObjectNode sinPassword = mapper.createObjectNode();
        sinPassword.put("EmployeeID", EMPLOYEE_ID);
        sinPassword.put("eMail", EMAIL);

        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(sinPassword));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "cualquiera"), http, salida));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void passwordVaciaEnSapNoHabilitaEntrarConPasswordVacia() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("")));

        ApiException e = assertThrows(ApiException.class, () -> controller.login(new LoginRequest(EMAIL, ""), http, salida));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    // --- usuario inexistente ---

    @Test
    void emailQueNoExisteDaLaMismaRespuestaQueUnaClaveEquivocada() {
        // Un 404 distinto del 401 le decía a cualquiera qué correos están cargados en SAP.
        when(sapClient.queryEmployeeByEmail("nadie@calimport.cl")).thenReturn(respuestaCon());
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("la-correcta")));

        ApiException inexistente = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest("nadie@calimport.cl", "cualquiera"), http, salida));
        ApiException claveMala = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "cualquiera"), http, salida));

        assertEquals(HttpStatus.UNAUTHORIZED, inexistente.getStatus());
        assertEquals(claveMala.getStatus(), inexistente.getStatus());
        assertEquals(claveMala.getMessage(), inexistente.getMessage());
        assertEquals(AuthController.CREDENCIALES_INVALIDAS, inexistente.getMessage());
    }

    @Test
    void respuestaDeSapSinArregloValueDevuelveUnauthorized() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(mapper.createObjectNode());

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "cualquiera"), http, salida));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    // --- fuerza bruta ---

    @Test
    void trasVariosFallosElCorreoQuedaBloqueadoSinConsultarSap() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("la-correcta")));

        for (int i = 0; i < 3; i++) {
            assertThrows(ApiException.class, () -> controller.login(new LoginRequest(EMAIL, "mala"), http, salida));
        }

        // Bloqueada, ni la clave correcta entra, y SAP ya no se consulta.
        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "la-correcta"), http, salida));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        verify(sapClient, times(3)).queryEmployeeByEmail(EMAIL);
        verifyNoInteractions(repartidorService);
    }

    @Test
    void probarMuchosCorreosDesdeLaMismaIpTerminaBloqueado() {
        when(sapClient.queryEmployeeByEmail(anyString())).thenReturn(respuestaCon());

        for (int i = 0; i < 5; i++) {
            String correo = "falso" + i + "@calimport.cl";
            assertThrows(ApiException.class, () -> controller.login(new LoginRequest(correo, "x"), http, salida));
        }

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest("otro@calimport.cl", "x"), http, salida));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        verify(sapClient, never()).queryEmployeeByEmail("otro@calimport.cl");
    }

    @Test
    void unLoginCorrectoLimpiaLosFallosDelCorreo() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(empleado("la-correcta")));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juan Perez", Rol.Despachador)).thenReturn("jwt");

        assertThrows(ApiException.class, () -> controller.login(new LoginRequest(EMAIL, "mala"), http, salida));
        assertThrows(ApiException.class, () -> controller.login(new LoginRequest(EMAIL, "mala"), http, salida));
        controller.login(new LoginRequest(EMAIL, "la-correcta"), http, salida);

        // Sin la limpieza, este tercer fallo bloquearía la cuenta.
        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "mala"), http, salida));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void laIpRealSaleDeXForwardedForSoloSiLaConexionVieneDeNginx() {
        MockHttpServletRequest desdeNginx = new MockHttpServletRequest();
        desdeNginx.setRemoteAddr("127.0.0.1");
        desdeNginx.addHeader("X-Forwarded-For", "1.2.3.4, 200.1.1.1");
        assertEquals("200.1.1.1", AuthController.ipCliente(desdeNginx));

        // Directo al puerto, la cabecera la escribe el cliente: se ignora.
        MockHttpServletRequest directo = new MockHttpServletRequest();
        directo.setRemoteAddr("10.9.9.9");
        directo.addHeader("X-Forwarded-For", "1.2.3.4");
        assertEquals("10.9.9.9", AuthController.ipCliente(directo));
    }

    // --- fallas de SAP ---

    @Test
    void sesionCaidaConSapDevuelveBadGateway() {
        when(sapClient.queryEmployeeByEmail(anyString()))
                .thenThrow(new SapUnauthorizedException("sesion expirada"));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "pass"), http, salida));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
    }

    @Test
    void sapCaidoDevuelveBadGatewayYNoUnErrorGenerico() {
        when(sapClient.queryEmployeeByEmail(anyString())).thenThrow(new RuntimeException("connection timeout"));

        ApiException e = assertThrows(ApiException.class,
                () -> controller.login(new LoginRequest(EMAIL, "pass"), http, salida));

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

        ApiException e = assertThrows(ApiException.class, () -> controller.login(new LoginRequest(EMAIL, "pass"), http, salida));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
        verify(repartidorService, never()).upsertDesdeSap(anyInt(), any(), any(), eq(true), any());
    }
}
