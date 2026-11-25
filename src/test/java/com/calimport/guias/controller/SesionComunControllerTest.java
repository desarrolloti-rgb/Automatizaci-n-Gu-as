package com.calimport.guias.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.calimport.guias.controller.dto.LoginResponse;
import com.calimport.guias.model.Rol;
import com.calimport.guias.sap.SapClient;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.security.SesionComun;
import com.calimport.guias.service.RepartidorService;
import com.calimport.guias.utils.ApiException;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * El canje de la sesión común por un token de Guías. Lo que importa es que una cookie bien
 * firmada no alcanza: cada canje vuelve a preguntarle a SAP, así que "permanente" se corta
 * solo si el empleado deja de estar activo, cambia de cargo o cambia su clave.
 */
@ExtendWith(MockitoExtension.class)
class SesionComunControllerTest {

    private static final String SECRETO = "secreto-compartido-solo-para-pruebas-0123456789";
    private static final String EMAIL = "jefe@calimport.cl";
    private static final int EMPLOYEE_ID = 9;
    private static final String CLAVE = "clave-del-jefe";

    @Mock
    private SapClient sapClient;
    @Mock
    private RepartidorService repartidorService;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private final ObjectMapper mapper = new ObjectMapper();
    private SesionComun sesionComun;
    private SesionComunController controller;

    @BeforeEach
    void setUp() {
        sesionComun = new SesionComun(SECRETO, "calimport_sesion", 30, true);
        controller = new SesionComunController(sesionComun, sapClient, repartidorService, jwtTokenProvider);
    }

    private ObjectNode jefe() {
        ObjectNode empleado = mapper.createObjectNode();
        empleado.put("EmployeeID", EMPLOYEE_ID);
        empleado.put("eMail", EMAIL);
        empleado.put("FirstName", "Juana");
        empleado.put("LastName", "Rojas");
        empleado.put("JobTitle", "Jefe Bodega");
        empleado.put("U_Password", CLAVE);
        return empleado;
    }

    private ObjectNode respuestaCon(ObjectNode... empleados) {
        ObjectNode respuesta = mapper.createObjectNode();
        var arreglo = respuesta.putArray("value");
        for (ObjectNode empleado : empleados) {
            arreglo.add(empleado);
        }
        return respuesta;
    }

    private MockHttpServletRequest conCookie(String valor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (valor != null) {
            request.setCookies(new Cookie("calimport_sesion", valor));
        }
        return request;
    }

    private MockHttpServletRequest conSesionDelJefe() {
        return conCookie(sesionComun.emitir(EMAIL, EMPLOYEE_ID, CLAVE));
    }

    private static String setCookie(ResponseEntity<?> respuesta) {
        return respuesta.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    }

    // --- canje correcto ---

    @Test
    void elJefeConSesionComunRecibeSuTokenSinEscribirLaClave() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(jefe()));
        when(jwtTokenProvider.generateToken(EMAIL, EMPLOYEE_ID, "Juana Rojas", Rol.JEFE_BODEGA)).thenReturn("jwt-jefe");

        ResponseEntity<?> respuesta = controller.renovar(conSesionDelJefe());

        assertEquals(HttpStatus.OK, respuesta.getStatusCode());
        assertEquals("jwt-jefe", ((LoginResponse) respuesta.getBody()).token());
        // Igual que el login: el canje refresca la copia local con lo que dice SAP hoy.
        verify(repartidorService).upsertDesdeSap(EMPLOYEE_ID, "Juana Rojas", EMAIL, true, Rol.JEFE_BODEGA);
    }

    @Test
    void cadaCanjeRenuevaLaCookie() {
        // "30 días sin uso": usarla vuelve a contar desde cero.
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(jefe()));
        when(jwtTokenProvider.generateToken(anyString(), anyInt(), anyString(), any())).thenReturn("jwt");

        String cookie = setCookie(controller.renovar(conSesionDelJefe()));

        assertNotNull(cookie);
        assertTrue(cookie.startsWith("calimport_sesion=") && !cookie.startsWith("calimport_sesion=;"), cookie);
        assertTrue(cookie.contains("Max-Age=2592000"), cookie);
        assertTrue(cookie.contains("Path=/"), cookie);
    }

    // --- sin sesión: 401 y SAP ni se consulta ---

    @Test
    void sinCookieResponde401SinConsultarSap() {
        ResponseEntity<?> respuesta = controller.renovar(conCookie(null));

        assertEquals(HttpStatus.UNAUTHORIZED, respuesta.getStatusCode());
        assertNull(setCookie(respuesta), "no hay nada que borrar");
        verifyNoInteractions(sapClient, repartidorService);
    }

    @Test
    void unaCookieFalsificadaSeBorraSinConsultarSap() {
        ResponseEntity<?> respuesta = controller.renovar(conCookie("inventada.firma"));

        assertEquals(HttpStatus.UNAUTHORIZED, respuesta.getStatusCode());
        assertTrue(setCookie(respuesta).contains("Max-Age=0"));
        verifyNoInteractions(sapClient);
    }

    @Test
    void sinSecretoConfiguradoNoCanjeaNiBorraLaCookieDeOtraInstancia() {
        // La instancia de prueba comparte dominio: la cookie que recibe es la de producción, y
        // borrarla sacaría al jefe de bodega de producción.
        SesionComunController apagado = new SesionComunController(
                new SesionComun("", "calimport_sesion", 30, true), sapClient, repartidorService, jwtTokenProvider);

        ResponseEntity<?> respuesta = apagado.renovar(conSesionDelJefe());

        assertEquals(HttpStatus.UNAUTHORIZED, respuesta.getStatusCode());
        assertNull(setCookie(respuesta));
        verifyNoInteractions(sapClient);
    }

    // --- SAP ya no lo respalda: 401 y se borra ---

    @Test
    void siCambioLaClaveEnSapLaSesionSeCorta() {
        ObjectNode conOtraClave = jefe();
        conOtraClave.put("U_Password", "la-clave-nueva");
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(conOtraClave));

        ResponseEntity<?> respuesta = controller.renovar(conSesionDelJefe());

        assertEquals(HttpStatus.UNAUTHORIZED, respuesta.getStatusCode());
        assertTrue(setCookie(respuesta).contains("Max-Age=0"));
        verify(jwtTokenProvider, never()).generateToken(anyString(), anyInt(), anyString(), any());
    }

    @Test
    void siDejoDeSerJefeDeBodegaLaSesionSeCorta() {
        // Solo el jefe de bodega tiene sesión común; un despachador vuelve a la de 12 h.
        ObjectNode bodeguero = jefe();
        bodeguero.put("JobTitle", "Bodeguero");
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(bodeguero));

        ResponseEntity<?> respuesta = controller.renovar(conSesionDelJefe());

        assertEquals(HttpStatus.UNAUTHORIZED, respuesta.getStatusCode());
        assertTrue(setCookie(respuesta).contains("Max-Age=0"));
        verifyNoInteractions(repartidorService);
    }

    @Test
    void siYaNoEstaActivoEnSapLaSesionSeCorta() {
        // queryEmployeeByEmail filtra Active eq 'tYES': un inactivo llega como lista vacía.
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon());

        ResponseEntity<?> respuesta = controller.renovar(conSesionDelJefe());

        assertEquals(HttpStatus.UNAUTHORIZED, respuesta.getStatusCode());
        assertTrue(setCookie(respuesta).contains("Max-Age=0"));
    }

    @Test
    void siElCorreoQuedoEnOtroEmpleadoNoEntraComoEl() {
        ObjectNode otro = jefe();
        otro.put("EmployeeID", 77);
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenReturn(respuestaCon(otro));

        assertEquals(HttpStatus.UNAUTHORIZED, controller.renovar(conSesionDelJefe()).getStatusCode());
    }

    // --- SAP caído: no es culpa de la sesión ---

    @Test
    void conSapCaidoResponde502YNoBorraLaSesion() {
        when(sapClient.queryEmployeeByEmail(EMAIL)).thenThrow(new RuntimeException("connection timeout"));

        ApiException e = assertThrows(ApiException.class, () -> controller.renovar(conSesionDelJefe()));

        // Con un 401, una caída de SAP de cinco minutos obligaría al jefe a volver a entrar.
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
    }

    // --- salir ---

    @Test
    void salirBorraLaCookieComun() {
        ResponseEntity<Void> respuesta = controller.salir();

        assertEquals(HttpStatus.NO_CONTENT, respuesta.getStatusCode());
        String cookie = setCookie(respuesta);
        assertTrue(cookie.startsWith("calimport_sesion=;") && cookie.contains("Max-Age=0") && cookie.contains("Path=/"),
                cookie);
    }

    @Test
    void salirSinSecretoNoTocaLaCookieDeOtraInstancia() {
        SesionComunController apagado = new SesionComunController(
                new SesionComun("", "calimport_sesion", 30, true), sapClient, repartidorService, jwtTokenProvider);

        assertNull(setCookie(apagado.salir()));
    }
}
