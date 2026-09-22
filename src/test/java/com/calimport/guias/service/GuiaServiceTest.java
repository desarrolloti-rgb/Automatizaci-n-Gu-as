package com.calimport.guias.service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.OrigenHorario;
import com.calimport.guias.model.Rol;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.security.UsuarioActual;
import com.calimport.guias.utils.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuiaServiceTest {

    private static final int DOC_ENTRY = 1001;
    private static final long FOLIO = 5555L;

    @Mock
    private GuiaRepository guiaRepository;

    /**
     * El envío a SAP se simula: acá se prueban las reglas de negocio, no la integración.
     * Que el mock no haga nada representa bien la realidad —el envío es best-effort y no
     * puede alterar el resultado local— y además deja comprobable que se lo llamó.
     */
    @Mock
    private SincronizacionSapService sincronizacionSap;

    private GuiaService service;

    @BeforeEach
    void setUp() {
        service = new GuiaService(guiaRepository, sincronizacionSap);
    }

    /** Guía tal como queda recién creada: PENDIENTE, sin repartidor ni evidencia. */
    private Guia guiaPendiente() {
        Guia guia = new Guia(DOC_ENTRY, FOLIO, "Cliente X", "Av. Siempre Viva 742");
        guia.setId(1L);
        return guia;
    }

    private Guia guiaConRepartidor() {
        Guia guia = guiaPendiente();
        guia.setRepartidorId(7);
        return guia;
    }

    /** save() en JPA devuelve la entidad persistida: se emula devolviendo la misma instancia. */
    private void devuelveLoQueGuarda() {
        when(guiaRepository.save(any(Guia.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- crear ---

    @Test
    void crearGuardaLaGuiaConElEstadoInicialCuandoElDocEntryNoExiste() {
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.empty());
        devuelveLoQueGuarda();

        Guia creada = service.crear(DOC_ENTRY, FOLIO, "Cliente X", "Av. Siempre Viva 742");

        assertEquals(DOC_ENTRY, creada.getDocEntry());
        assertEquals(FOLIO, creada.getFolio());
        assertEquals("Cliente X", creada.getCliente());
        assertEquals("Av. Siempre Viva 742", creada.getDireccion());
        // El resto del ciclo de vida arranca vacío: es lo que permite que el INSERT pase.
        assertEquals(EstadoGuia.PENDIENTE, creada.getEstado());
        assertNull(creada.getRepartidorId());
        assertNull(creada.getFechaEntrega());
        assertNull(creada.getUrlFoto());
        assertNull(creada.getHashFoto());
        assertFalse(creada.isRecibidaPorRepartidor());
        // Nace sincronizada: SAP ya la tiene como 'P' y no hay nada que contarle. El flag
        // baja a false recién cuando la app cambia el estado y ese cambio no ha llegado.
        assertTrue(creada.isSincronizada());
    }

    @Test
    void crearRechazaConConflictSiYaExisteUnaGuiaParaEseDocEntry() {
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(guiaPendiente()));

        ApiException e = assertThrows(ApiException.class,
                () -> service.crear(DOC_ENTRY, FOLIO, "Cliente X", "Direccion"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    // --- sincronizarDesdeSap (upsert) ---

    @Test
    void sincronizarCreaLaGuiaCuandoEsLaPrimeraVezQueLlegaDeSap() {
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.empty());
        devuelveLoQueGuarda();

        Guia guia = service.sincronizarDesdeSap(DOC_ENTRY, FOLIO, "Cliente X", "Av. Siempre Viva 742", null);

        assertEquals(DOC_ENTRY, guia.getDocEntry());
        assertEquals("Cliente X", guia.getCliente());
        assertEquals(EstadoGuia.PENDIENTE, guia.getEstado());
    }

    @Test
    void sincronizarRefrescaLosDatosDeUnaGuiaPendienteEnLugarDeRebotar() {
        // A diferencia de crear(), encontrarla no es un 409: es el caso normal a partir
        // de la segunda sincronización.
        Guia yaImportada = guiaPendiente();
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(yaImportada));
        devuelveLoQueGuarda();

        Guia guia = service.sincronizarDesdeSap(DOC_ENTRY, 9999L, "Cliente Corregido", "Nueva Direccion 123", null);

        assertSame(yaImportada, guia);
        assertEquals(9999L, guia.getFolio());
        assertEquals("Cliente Corregido", guia.getCliente());
        assertEquals("Nueva Direccion 123", guia.getDireccion());
    }

    @Test
    void sincronizarNoPisaUnaGuiaYaEntregada() {
        // Lo guardado documenta lo que se entregó: reescribirlo borraría la evidencia.
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        entregada.setUrlFoto("https://fotos/1.jpg");
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(entregada));

        Guia guia = service.sincronizarDesdeSap(DOC_ENTRY, 9999L, "Cliente Corregido", "Otra Direccion", null);

        assertEquals("Cliente X", guia.getCliente());
        assertEquals(FOLIO, guia.getFolio());
        assertEquals("https://fotos/1.jpg", guia.getUrlFoto());
        verify(guiaRepository, never()).save(any());
    }

    @Test
    void sincronizarNoPisaUnaGuiaYaRechazada() {
        Guia rechazada = guiaConRepartidor();
        rechazada.setEstado(EstadoGuia.RECHAZADA);
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(rechazada));

        service.sincronizarDesdeSap(DOC_ENTRY, 9999L, "Cliente Corregido", "Otra Direccion", null);

        verify(guiaRepository, never()).save(any());
    }

    @Test
    void sincronizarNuncaTocaLosCamposQueGeneraLaApp() {
        // Solo viajan los cuatro datos de SAP; el reparto es asunto de la app.
        Guia enReparto = guiaConRepartidor();
        enReparto.setRecibidaPorRepartidor(true);
        enReparto.setFechaRecepcionRepartidor(Instant.now());
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(enReparto));
        devuelveLoQueGuarda();

        Guia guia = service.sincronizarDesdeSap(DOC_ENTRY, FOLIO, "Cliente X", "Direccion Corregida", null);

        assertEquals(7, guia.getRepartidorId());
        assertTrue(guia.isRecibidaPorRepartidor());
        assertNotNull(guia.getFechaRecepcionRepartidor());
        assertEquals("Direccion Corregida", guia.getDireccion());
    }

    @Test
    void sincronizarBorraLasCoordenadasSiSapCorrigeLaDireccion() {
        // Las coordenadas viejas mandarian al repartidor a la direccion equivocada.
        Guia ubicada = guiaPendiente();
        ubicada.setLatitud(-33.45);
        ubicada.setLongitud(-70.66);
        ubicada.setUbicacionAproximada(true);
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(ubicada));
        devuelveLoQueGuarda();

        Guia guia = service.sincronizarDesdeSap(DOC_ENTRY, FOLIO, "Cliente X", "Direccion Corregida", null);

        assertFalse(guia.tieneUbicacion());
        assertFalse(guia.isUbicacionAproximada());
    }

    @Test
    void sincronizarConservaLasCoordenadasSiLaDireccionNoCambio() {
        Guia ubicada = guiaPendiente();
        ubicada.setLatitud(-33.45);
        ubicada.setLongitud(-70.66);
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(ubicada));
        devuelveLoQueGuarda();

        Guia guia = service.sincronizarDesdeSap(DOC_ENTRY, FOLIO, "Cliente X", "Av. Siempre Viva 742", null);

        assertTrue(guia.tieneUbicacion());
    }

    @Test
    void sincronizarDescartaElHorarioInterpretadoSiCambiaElComentario() {
        Guia interpretada = guiaPendiente();
        interpretada.setComentario("recibe en la mañana");
        interpretada.setVentanaDesde(LocalTime.of(9, 0));
        interpretada.setVentanaHasta(LocalTime.of(13, 0));
        interpretada.setOrigenHorario(OrigenHorario.COMENTARIO);
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(interpretada));
        devuelveLoQueGuarda();

        Guia guia = service.sincronizarDesdeSap(DOC_ENTRY, FOLIO, "Cliente X", "Av. Siempre Viva 742",
                "recibe en la tarde");

        assertEquals("recibe en la tarde", guia.getComentario());
        assertNull(guia.getVentanaDesde());
        assertNull(guia.getOrigenHorario());
    }

    @Test
    void sincronizarRespetaElHorarioQueDefinioElBodeguero() {
        Guia definida = guiaPendiente();
        definida.setComentario("recibe en la mañana");
        definida.setVentanaDesde(LocalTime.of(15, 0));
        definida.setOrigenHorario(OrigenHorario.BODEGA);
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.of(definida));
        devuelveLoQueGuarda();

        Guia guia = service.sincronizarDesdeSap(DOC_ENTRY, FOLIO, "Cliente X", "Av. Siempre Viva 742", "otra cosa");

        assertEquals(LocalTime.of(15, 0), guia.getVentanaDesde());
        assertEquals(OrigenHorario.BODEGA, guia.getOrigenHorario());
    }

    @Test
    void sincronizarGuardaUnComentarioEnBlancoComoNull() {
        when(guiaRepository.findByDocEntry(DOC_ENTRY)).thenReturn(Optional.empty());
        devuelveLoQueGuarda();

        assertNull(service.sincronizarDesdeSap(DOC_ENTRY, FOLIO, "Cliente X", "Direccion", "  ").getComentario());
    }

    // --- horario ---

    @Test
    void definirHorarioLoMarcaComoDelBodeguero() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaPendiente()));
        devuelveLoQueGuarda();

        Guia guia = service.definirHorario(1L, LocalTime.of(9, 0), LocalTime.of(13, 0), " Llamar antes ");

        assertEquals(LocalTime.of(9, 0), guia.getVentanaDesde());
        assertEquals(LocalTime.of(13, 0), guia.getVentanaHasta());
        assertEquals("Llamar antes", guia.getNotaEntrega());
        assertEquals(OrigenHorario.BODEGA, guia.getOrigenHorario());
    }

    @Test
    void definirHorarioVacioDevuelveElControlAlComentario() {
        Guia definida = guiaPendiente();
        definida.setVentanaDesde(LocalTime.of(9, 0));
        definida.setOrigenHorario(OrigenHorario.BODEGA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(definida));
        devuelveLoQueGuarda();

        Guia guia = service.definirHorario(1L, null, null, " ");

        assertNull(guia.getVentanaDesde());
        assertNull(guia.getOrigenHorario());
    }

    @Test
    void definirHorarioRechazaUnRangoAlReves() {
        ApiException e = assertThrows(ApiException.class,
                () -> service.definirHorario(1L, LocalTime.of(13, 0), LocalTime.of(9, 0), null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        verify(guiaRepository, never()).findById(any());
    }

    @Test
    void definirHorarioRechazaUnaGuiaYaResuelta() {
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        ApiException e = assertThrows(ApiException.class,
                () -> service.definirHorario(1L, LocalTime.of(9, 0), null, null));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    void guardarHorarioInterpretadoNoPisaLoQueDefinioElBodeguero() {
        // El bodeguero pudo corregirlo mientras Gemini respondia.
        Guia definida = guiaPendiente();
        definida.setVentanaDesde(LocalTime.of(15, 0));
        definida.setOrigenHorario(OrigenHorario.BODEGA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(definida));

        Guia guia = service.guardarHorarioInterpretado(1L, LocalTime.of(9, 0), LocalTime.of(13, 0), null);

        assertEquals(LocalTime.of(15, 0), guia.getVentanaDesde());
        verify(guiaRepository, never()).save(any());
    }

    @Test
    void guardarHorarioInterpretadoMarcaElOrigenAunqueNoHayaHorario() {
        // Asi no se vuelve a pagar la interpretacion de un comentario que no dice nada del horario.
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaPendiente()));
        devuelveLoQueGuarda();

        Guia guia = service.guardarHorarioInterpretado(1L, null, null, null);

        assertEquals(OrigenHorario.COMENTARIO, guia.getOrigenHorario());
    }

    // --- lecturas ---

    @Test
    void obtenerPorIdDevuelveLaGuiaCuandoExiste() {
        Guia guia = guiaPendiente();
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guia));

        assertSame(guia, service.obtenerPorId(1L));
    }

    @Test
    void obtenerPorIdLanzaNotFoundCuandoNoExiste() {
        when(guiaRepository.findById(99L)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.obtenerPorId(99L));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void listarPorEstadoConsultaSoloEseEstado() {
        List<Guia> esperadas = List.of(guiaPendiente());
        when(guiaRepository.findByEstado(EstadoGuia.PENDIENTE)).thenReturn(esperadas);

        assertEquals(esperadas, service.listarPorEstado(EstadoGuia.PENDIENTE));
    }

    @Test
    void listarPorRepartidorConsultaSoloEseRepartidor() {
        List<Guia> esperadas = List.of(guiaConRepartidor());
        when(guiaRepository.findByRepartidorId(7)).thenReturn(esperadas);

        assertEquals(esperadas, service.listarPorRepartidor(7));
    }

    // --- lecturas según el rol ---

    private static final UsuarioActual JEFE = new UsuarioActual(9, Rol.JEFE_BODEGA);
    private static final UsuarioActual REPARTIDOR_7 = new UsuarioActual(7, Rol.REPARTIDOR);

    @Test
    void elJefeDeBodegaListaTodasLasGuias() {
        List<Guia> todas = List.of(guiaPendiente(), guiaConRepartidor());
        when(guiaRepository.findAll()).thenReturn(todas);

        assertEquals(todas, service.listarPara(JEFE, null, null));
    }

    @Test
    void elJefeDeBodegaPuedeFiltrarPorCualquierRepartidor() {
        List<Guia> esperadas = List.of(guiaConRepartidor());
        when(guiaRepository.findByRepartidorId(7)).thenReturn(esperadas);

        assertEquals(esperadas, service.listarPara(JEFE, null, 7));
    }

    @Test
    void unRepartidorSinFiltrosVeSoloLasSuyas() {
        List<Guia> suyas = List.of(guiaConRepartidor());
        when(guiaRepository.findByRepartidorId(7)).thenReturn(suyas);

        assertEquals(suyas, service.listarPara(REPARTIDOR_7, null, null));
        verify(guiaRepository, never()).findAll();
    }

    @Test
    void elEstadoDeUnRepartidorFiltraDentroDeLasSuyasYNoSobreTodas() {
        List<Guia> suyas = List.of(guiaConRepartidor());
        when(guiaRepository.findByRepartidorIdAndEstado(7, EstadoGuia.PENDIENTE)).thenReturn(suyas);

        assertEquals(suyas, service.listarPara(REPARTIDOR_7, EstadoGuia.PENDIENTE, null));
        verify(guiaRepository, never()).findByEstado(any());
    }

    @Test
    void unRepartidorQuePideLasGuiasDeOtroRecibeForbidden() {
        ApiException e = assertThrows(ApiException.class, () -> service.listarPara(REPARTIDOR_7, null, 8));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        verify(guiaRepository, never()).findByRepartidorId(any());
    }

    @Test
    void unRepartidorQuePasaSuPropioIdVeLasSuyas() {
        List<Guia> suyas = List.of(guiaConRepartidor());
        when(guiaRepository.findByRepartidorId(7)).thenReturn(suyas);

        assertEquals(suyas, service.listarPara(REPARTIDOR_7, null, 7));
    }

    @Test
    void elJefeDeBodegaObtieneCualquierGuia() {
        Guia sinAsignar = guiaPendiente();
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(sinAsignar));

        assertSame(sinAsignar, service.obtenerPara(1L, JEFE));
    }

    @Test
    void unRepartidorObtieneUnaGuiaSuya() {
        Guia suya = guiaConRepartidor();
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(suya));

        assertSame(suya, service.obtenerPara(1L, REPARTIDOR_7));
    }

    @Test
    void unRepartidorQuePideUnaGuiaAjenaRecibeForbiddenYNoNotFound() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));

        ApiException e = assertThrows(ApiException.class,
                () -> service.obtenerPara(1L, new UsuarioActual(8, Rol.REPARTIDOR)));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    // --- asignarRepartidor ---

    @Test
    void asignarRepartidorGuardaElIdCuandoLaGuiaEstaPendiente() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaPendiente()));
        devuelveLoQueGuarda();

        Guia resultado = service.asignarRepartidor(1L, 7);

        assertEquals(7, resultado.getRepartidorId());
        assertEquals(EstadoGuia.PENDIENTE, resultado.getEstado());
    }

    @Test
    void asignarRepartidorRechazaConBadRequestSiNoVianeElId() {
        ApiException e = assertThrows(ApiException.class, () -> service.asignarRepartidor(1L, null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        // Se valida antes de tocar la BD: ni siquiera busca la guía.
        verify(guiaRepository, never()).findById(any());
    }

    @Test
    void asignarRepartidorRechazaConConflictSiLaGuiaYaFueResuelta() {
        Guia entregada = guiaPendiente();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        ApiException e = assertThrows(ApiException.class, () -> service.asignarRepartidor(1L, 7));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    // --- marcarRecibidaPorRepartidor ---

    @Test
    void marcarRecibidaDejaConstanciaDeLaFechaDeRetiro() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));
        devuelveLoQueGuarda();

        Instant antes = Instant.now();
        Guia resultado = service.marcarRecibidaPorRepartidor(1L, 7);
        Instant despues = Instant.now();

        assertTrue(resultado.isRecibidaPorRepartidor());
        assertNotNull(resultado.getFechaRecepcionRepartidor());
        assertFalse(resultado.getFechaRecepcionRepartidor().isBefore(antes));
        assertFalse(resultado.getFechaRecepcionRepartidor().isAfter(despues));
        // Retirar la guía no la resuelve: sigue PENDIENTE hasta entregar o rechazar.
        assertEquals(EstadoGuia.PENDIENTE, resultado.getEstado());
    }

    @Test
    void marcarRecibidaRechazaConForbiddenSiLaGuiaNoTieneRepartidor() {
        // Sin asignar no es de nadie: tampoco de quien la pide.
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaPendiente()));

        ApiException e = assertThrows(ApiException.class, () -> service.marcarRecibidaPorRepartidor(1L, 7));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    @Test
    void marcarRecibidaRechazaConForbiddenSiLaGuiaEsDeOtroRepartidor() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));

        ApiException e = assertThrows(ApiException.class, () -> service.marcarRecibidaPorRepartidor(1L, 8));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    @Test
    void marcarRecibidaRechazaConConflictSiLaGuiaYaFueResuelta() {
        Guia rechazada = guiaConRepartidor();
        rechazada.setEstado(EstadoGuia.RECHAZADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(rechazada));

        ApiException e = assertThrows(ApiException.class, () -> service.marcarRecibidaPorRepartidor(1L, 7));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    // --- entregar ---

    @Test
    void entregarGuardaEstadoFechaYEvidenciaFotografica() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));
        devuelveLoQueGuarda();

        Instant antes = Instant.now();
        Guia resultado = service.entregar(1L, 7,"https://fotos/guia-1.jpg", "abc123hash");
        Instant despues = Instant.now();

        assertEquals(EstadoGuia.ENTREGADA, resultado.getEstado());
        assertEquals("https://fotos/guia-1.jpg", resultado.getUrlFoto());
        assertEquals("abc123hash", resultado.getHashFoto());
        assertNotNull(resultado.getFechaEntrega());
        assertFalse(resultado.getFechaEntrega().isBefore(antes));
        assertFalse(resultado.getFechaEntrega().isAfter(despues));
        // La entrega no se marca sincronizada sola: eso lo hace el push a SAP.
        assertFalse(resultado.isSincronizada());
    }

    @Test
    void entregarExigeUrlDeFoto() {
        ApiException sinUrl = assertThrows(ApiException.class, () -> service.entregar(1L, 7,null, "hash"));
        ApiException urlEnBlanco = assertThrows(ApiException.class, () -> service.entregar(1L, 7,"   ", "hash"));

        assertEquals(HttpStatus.BAD_REQUEST, sinUrl.getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, urlEnBlanco.getStatus());
        verify(guiaRepository, never()).findById(any());
    }

    @Test
    void entregarExigeHashDeFoto() {
        ApiException sinHash = assertThrows(ApiException.class, () -> service.entregar(1L, 7,"https://f/1.jpg", null));
        ApiException hashEnBlanco = assertThrows(ApiException.class, () -> service.entregar(1L, 7,"https://f/1.jpg", "  "));

        assertEquals(HttpStatus.BAD_REQUEST, sinHash.getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, hashEnBlanco.getStatus());
        verify(guiaRepository, never()).findById(any());
    }

    @Test
    void entregarRechazaConConflictUnaGuiaYaEntregada() {
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        ApiException e = assertThrows(ApiException.class,
                () -> service.entregar(1L, 7,"https://fotos/otra.jpg", "otrohash"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    @Test
    void entregarUnaGuiaDeOtroRepartidorEsForbiddenAunqueEsteResuelta() {
        // La pertenencia se revisa antes que el estado: a un tercero no se le cuenta
        // en qué quedó una guía que no es suya.
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        ApiException e = assertThrows(ApiException.class,
                () -> service.entregar(1L, 8, "https://fotos/otra.jpg", "otrohash"));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    // --- rechazar ---

    @Test
    void rechazarMarcaLaGuiaSinExigirEvidenciaFotografica() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));
        devuelveLoQueGuarda();

        Guia resultado = service.rechazar(1L, 7, "El cliente no tenía espacio en bodega");

        assertEquals(EstadoGuia.RECHAZADA, resultado.getEstado());
        assertNotNull(resultado.getFechaEntrega());
        assertEquals("El cliente no tenía espacio en bodega", resultado.getMotivoRechazo());
        // Un rechazo no tiene foto de recepción: los campos quedan vacíos a propósito.
        assertNull(resultado.getUrlFoto());
        assertNull(resultado.getHashFoto());
    }

    @Test
    void rechazarGuardaElMotivoSinEspaciosSobrantes() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));
        devuelveLoQueGuarda();

        Guia resultado = service.rechazar(1L, 7, "   Se equivocaron de producto  ");

        assertEquals("Se equivocaron de producto", resultado.getMotivoRechazo());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void rechazarSinMotivoEsBadRequest(String motivo) {
        ApiException e = assertThrows(ApiException.class, () -> service.rechazar(1L, 7, motivo));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        // Se valida antes de tocar la base: un rechazo sin motivo no llega ni a buscarse.
        verify(guiaRepository, never()).findById(any());
        verify(guiaRepository, never()).save(any());
    }

    @Test
    void rechazarRechazaConConflictUnaGuiaYaResuelta() {
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        ApiException e = assertThrows(ApiException.class, () -> service.rechazar(1L, 7, "No estaba el encargado"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    void rechazarUnaGuiaDeOtroRepartidorEsForbidden() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));

        ApiException e = assertThrows(ApiException.class, () -> service.rechazar(1L, 8, "No estaba el encargado"));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    // --- sincronización con SAP ---

    @Test
    void reintentarSincronizacionVuelveAEmpujarLaGuiaASap() {
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        entregada.setSincronizada(false);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        service.reintentarSincronizacion(1L);

        // No levanta el flag por su cuenta: eso lo decide el envío según si SAP contestó.
        // Marcarla sincronizada sin haberla enviado seria mentir y nadie reintentaria.
        verify(sincronizacionSap).empujar(entregada);
        assertFalse(entregada.isSincronizada());
    }

    @Test
    void cadaCambioDeEstadoSeEmpujaASapYQuedaMarcadoHastaQueLlegue() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));
        devuelveLoQueGuarda();

        Guia resultado = service.rechazar(1L, 7, "No estaba el encargado");

        verify(sincronizacionSap).empujar(resultado);
        // El envío es un mock que no hace nada: queda como quedaría si SAP no contestara.
        assertFalse(resultado.isSincronizada());
    }

    // --- reabrir (flujo R -> P del jefe de bodega) ---

    @Test
    void reabrirDevuelveUnaRechazadaAPendienteYLaDejaSinDuenoNiMotivo() {
        Guia rechazada = guiaConRepartidor();
        rechazada.setEstado(EstadoGuia.RECHAZADA);
        rechazada.setMotivoRechazo("No tenían espacio");
        rechazada.setFechaEntrega(Instant.now());
        rechazada.setRecibidaPorRepartidor(true);
        rechazada.setFechaRecepcionRepartidor(Instant.now());
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(rechazada));
        devuelveLoQueGuarda();

        Guia resultado = service.reabrir(1L);

        assertEquals(EstadoGuia.PENDIENTE, resultado.getEstado());
        assertNull(resultado.getMotivoRechazo());
        assertNull(resultado.getFechaEntrega());
        // Sin repartidor y sin retirar: bodega la puede reasignar a otro.
        assertNull(resultado.getRepartidorId());
        assertFalse(resultado.isRecibidaPorRepartidor());
        assertNull(resultado.getFechaRecepcionRepartidor());
        verify(sincronizacionSap).empujar(resultado);
    }

    @Test
    void reabrirUnaEntregadaEsConflict() {
        Guia entregada = guiaConRepartidor();
        entregada.setEstado(EstadoGuia.ENTREGADA);
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(entregada));

        ApiException e = assertThrows(ApiException.class, () -> service.reabrir(1L));

        // El cliente firmó y hay una foto que lo prueba: eso no se deshace.
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }

    @Test
    void reabrirUnaPendienteEsConflict() {
        when(guiaRepository.findById(1L)).thenReturn(Optional.of(guiaConRepartidor()));

        ApiException e = assertThrows(ApiException.class, () -> service.reabrir(1L));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(guiaRepository, never()).save(any());
    }
}
