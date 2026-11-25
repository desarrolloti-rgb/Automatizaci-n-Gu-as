package com.calimport.guias.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.EnvioExterno;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.ResultadoEnvio;
import com.calimport.guias.repository.EnvioExternoRepository;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.service.EnviosExternosService.EnvioEncontrado;
import com.calimport.guias.service.EnviosExternosService.EvidenciaGuardada;
import com.calimport.guias.service.EnviosExternosService.Pendiente;
import com.calimport.guias.service.EnviosExternosService.ResultadoBusqueda;
import com.calimport.guias.utils.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corre contra H2 con las mismas migraciones de producción: las reglas dependen de lo que la
 * base garantiza (una fila por guía, un número de envío por guía), y eso no se prueba con mocks.
 */
@SpringBootTest
@Transactional
class EnviosExternosServiceTest {

    private static final String SAMEX = "SAMEX";
    private static final Instant ENTREGA = Instant.parse("2026-11-23T13:03:56Z");

    @Autowired
    private EnviosExternosService service;

    @Autowired
    private GuiaRepository guiaRepository;

    @Autowired
    private EnvioExternoRepository envioRepository;

    private int docEntry = 9000;

    /** Una guía como la deja bodega al marcarla SAMEX: pendiente, sin repartidor y recibida. */
    private Guia guiaDeSamex(long folio) {
        Guia guia = new Guia(docEntry++, folio, "Cliente " + folio, "Direccion " + folio);
        guia.setTransportista(SAMEX);
        guia.setRecibidaPorRepartidor(true);
        guia.setFechaRecepcionRepartidor(Instant.parse("2026-11-23T12:00:00Z"));
        return guiaRepository.saveAndFlush(guia);
    }

    private static EnvioEncontrado envio(String numero, Instant entrega, String... referencias) {
        return new EnvioEncontrado(numero, entrega == null ? "En reparto" : "Entregada movilidad 23/11/2026",
                List.of(referencias), entrega);
    }

    private List<String> registrar(Guia guia, EnvioEncontrado... envios) {
        return service.registrar(SAMEX, List.of(new ResultadoBusqueda(guia.getId(), guia.getFolio(), List.of(envios))));
    }

    private EnvioExterno seguimientoDe(Guia guia) {
        return envioRepository.findByGuiaId(guia.getId()).orElseThrow();
    }

    // --- pendientes: qué guías tiene que buscar el lector ---

    @Test
    void pendientesSonSoloLasMarcadasSamexQueSiguenPendientes() {
        Guia marcada = guiaDeSamex(311001);
        Guia entregada = guiaDeSamex(311002);
        entregada.setEstado(EstadoGuia.ENTREGADA);
        guiaRepository.saveAndFlush(entregada);
        Guia sinMarcar = guiaRepository.saveAndFlush(new Guia(docEntry++, 311003L, "Cliente", "Direccion"));
        Guia deOtro = guiaDeSamex(311004);
        deOtro.setTransportista("OTRO");
        guiaRepository.saveAndFlush(deOtro);

        List<Pendiente> pendientes = service.pendientes(SAMEX);

        assertEquals(List.of(new Pendiente(marcada.getId(), 311001L, null)), pendientes);
        assertFalse(pendientes.stream().anyMatch(p -> p.guiaId().equals(sinMarcar.getId())));
    }

    @Test
    void primeroLasNuncaBuscadasYDespuesLaDeBusquedaMasAntigua() {
        Guia reciente = guiaDeSamex(311011);
        Guia nunca = guiaDeSamex(311012);
        Guia antigua = guiaDeSamex(311013);
        registrar(reciente);
        registrar(antigua);
        // Las horas se fijan a mano: dos búsquedas seguidas pueden caer en el mismo instante.
        fijarUltimaBusqueda(reciente, Instant.parse("2026-11-23T15:00:00Z"));
        fijarUltimaBusqueda(antigua, Instant.parse("2026-11-23T11:00:00Z"));

        List<Long> orden = service.pendientes(SAMEX).stream().map(Pendiente::guiaId).toList();

        assertEquals(List.of(nunca.getId(), antigua.getId(), reciente.getId()), orden);
    }

    private void fijarUltimaBusqueda(Guia guia, Instant cuando) {
        EnvioExterno seguimiento = seguimientoDe(guia);
        seguimiento.setUltimaBusqueda(cuando);
        envioRepository.saveAndFlush(seguimiento);
    }

    @Test
    void conElEnvioYaConfirmadoSeBuscaPorSuNumero() {
        Guia guia = guiaDeSamex(311021);
        registrar(guia, envio("999905761651", null, "CLIENTE FINAL", "311021"));

        assertEquals(List.of(new Pendiente(guia.getId(), 311021L, "999905761651")), service.pendientes(SAMEX));
    }

    // --- registrar: lo que el lector encontró ---

    @Test
    void sinEnviosQuedaNoEncontrado() {
        Guia guia = guiaDeSamex(311031);

        List<String> necesitanEvidencia = registrar(guia);

        EnvioExterno seguimiento = seguimientoDe(guia);
        assertEquals(ResultadoEnvio.NO_ENCONTRADO, seguimiento.getResultado());
        assertNull(seguimiento.getNumero());
        assertTrue(seguimiento.getMotivo().contains("311031"), seguimiento.getMotivo());
        assertEquals(seguimiento.getPrimeraBusqueda(), seguimiento.getUltimaBusqueda());
        assertTrue(necesitanEvidencia.isEmpty());
    }

    @Test
    void variosEnviosConElMismoFolioEsConflicto() {
        Guia guia = guiaDeSamex(311041);

        registrar(guia, envio("999900000001", null, "311041"), envio("999900000002", null, "311041"));

        EnvioExterno seguimiento = seguimientoDe(guia);
        assertEquals(ResultadoEnvio.CONFLICTO, seguimiento.getResultado());
        assertNull(seguimiento.getNumero());
        assertTrue(seguimiento.getMotivo().contains("999900000001")
                && seguimiento.getMotivo().contains("999900000002"), seguimiento.getMotivo());
    }

    @Test
    void unEnvioSinElFolioEntreSusReferenciasEsConflicto() {
        // La búsqueda por referencia puede traer coincidencias parciales (3110 trae 311051).
        Guia guia = guiaDeSamex(3110);

        registrar(guia, envio("999900000003", null, "CLIENTE FINAL", "311051"));

        EnvioExterno seguimiento = seguimientoDe(guia);
        assertEquals(ResultadoEnvio.CONFLICTO, seguimiento.getResultado());
        assertNull(seguimiento.getNumero());
    }

    @Test
    void unEnvioQueYaEsDeOtraGuiaEsConflicto() {
        Guia primera = guiaDeSamex(311061);
        Guia segunda = guiaDeSamex(311062);
        registrar(primera, envio("999900000004", null, "311061", "311062"));

        registrar(segunda, envio("999900000004", null, "311061", "311062"));

        assertEquals(ResultadoEnvio.ASOCIADO, seguimientoDe(primera).getResultado());
        EnvioExterno seguimiento = seguimientoDe(segunda);
        assertEquals(ResultadoEnvio.CONFLICTO, seguimiento.getResultado());
        assertNull(seguimiento.getNumero());
        assertTrue(seguimiento.getMotivo().contains("311061"), seguimiento.getMotivo());
    }

    @Test
    void unSoloEnvioConElFolioQuedaAsociadoConLoQueDiceSamex() {
        Guia guia = guiaDeSamex(311071);

        List<String> necesitanEvidencia = registrar(guia, envio("999900000005", null, "CLIENTE FINAL", "311071", "600777"));

        EnvioExterno seguimiento = seguimientoDe(guia);
        assertEquals(ResultadoEnvio.ASOCIADO, seguimiento.getResultado());
        assertEquals("999900000005", seguimiento.getNumero());
        assertEquals("En reparto", seguimiento.getEstadoPortal());
        assertEquals("CLIENTE FINAL, 311071, 600777", seguimiento.getReferencias());
        assertNull(seguimiento.getMotivo());
        assertNull(seguimiento.getFechaEntrega());
        // Todavía no se entrega: no hay PDF que pedir.
        assertTrue(necesitanEvidencia.isEmpty());
    }

    @Test
    void elFolioSeReconoceAunqueSamexLoTengaConCerosAdelante() {
        Guia guia = guiaDeSamex(311081);

        registrar(guia, envio("999900000006", null, "0311081"));

        assertEquals(ResultadoEnvio.ASOCIADO, seguimientoDe(guia).getResultado());
    }

    @Test
    void unaEntregaSinEvidenciaPideElPdf() {
        Guia guia = guiaDeSamex(311091);

        List<String> necesitanEvidencia = registrar(guia, envio("999900000007", ENTREGA, "311091"));

        assertEquals(List.of("999900000007"), necesitanEvidencia);
        assertEquals(ENTREGA, seguimientoDe(guia).getFechaEntrega());
    }

    @Test
    void unaEntregaQueYaTieneEvidenciaNoLaPideDeNuevo() {
        Guia guia = guiaDeSamex(311101);
        registrar(guia, envio("999900000008", ENTREGA, "311101"));
        EnvioExterno seguimiento = seguimientoDe(guia);
        seguimiento.setEvidenciaUrl("/api/fotos/guia-1-abc.pdf");
        envioRepository.saveAndFlush(seguimiento);

        assertTrue(registrar(guia, envio("999900000008", ENTREGA, "311101")).isEmpty());
    }

    @Test
    void registrarDosVecesLoMismoNoDuplicaFilasYSoloAvanzaLaUltimaBusqueda() {
        Guia guia = guiaDeSamex(311111);
        registrar(guia, envio("999900000009", null, "311111"));
        Instant primera = seguimientoDe(guia).getPrimeraBusqueda();
        long filas = envioRepository.count();

        registrar(guia, envio("999900000009", null, "311111"));

        assertEquals(filas, envioRepository.count());
        EnvioExterno seguimiento = seguimientoDe(guia);
        assertEquals(primera, seguimiento.getPrimeraBusqueda());
        assertFalse(seguimiento.getUltimaBusqueda().isBefore(primera));
        assertEquals(ResultadoEnvio.ASOCIADO, seguimiento.getResultado());
    }

    // --- lo que se ignora: la guía ya no es del transportista o ya se cerró ---

    @Test
    void unaGuiaResueltaSeIgnoraYNoCambiaNada() {
        Guia guia = guiaDeSamex(311121);
        guia.setEstado(EstadoGuia.ENTREGADA);
        guiaRepository.saveAndFlush(guia);

        List<String> necesitanEvidencia = registrar(guia, envio("999900000010", ENTREGA, "311121"));

        assertTrue(necesitanEvidencia.isEmpty());
        assertTrue(envioRepository.findByGuiaId(guia.getId()).isEmpty());
        assertEquals(EstadoGuia.ENTREGADA, guiaRepository.findById(guia.getId()).orElseThrow().getEstado());
    }

    @Test
    void unaGuiaQueBodegaDesmarcoSeIgnora() {
        Guia guia = guiaDeSamex(311131);
        guia.setTransportista(null);
        guiaRepository.saveAndFlush(guia);

        registrar(guia, envio("999900000011", null, "311131"));

        assertTrue(envioRepository.findByGuiaId(guia.getId()).isEmpty());
    }

    @Test
    void unResultadoConOtroFolioQueElDeLaGuiaSeIgnora() {
        Guia guia = guiaDeSamex(311141);

        service.registrar(SAMEX, List.of(new ResultadoBusqueda(guia.getId(), 999L,
                List.of(envio("999900000012", null, "999")))));

        assertTrue(envioRepository.findByGuiaId(guia.getId()).isEmpty());
    }

    @Test
    void unResultadoSinGuiaSeIgnoraSinRomperLosDemas() {
        // Lo que manda el lector llega por HTTP: un elemento mal armado no tiene que llevarse
        // por delante el resto de la corrida.
        Guia guia = guiaDeSamex(311161);

        List<String> necesitanEvidencia = service.registrar(SAMEX, List.of(
                new ResultadoBusqueda(null, 311161L, List.of()),
                new ResultadoBusqueda(guia.getId(), guia.getFolio(), List.of(envio("999900000013", ENTREGA, "311161")))));

        assertEquals(List.of("999900000013"), necesitanEvidencia);
    }

    // --- la evidencia (el PDF del albarán) y el cierre de la guía ---

    private static final byte[] PDF = "%PDF-1.4\n%albaran de prueba".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private GuiaService guiaService;

    @Autowired
    private AlmacenamientoFotos almacenamiento;

    private EnviosExternosService conModo(String modo) {
        return new EnviosExternosService(guiaRepository, envioRepository, guiaService, almacenamiento, modo);
    }

    @Test
    void enModoRegistrarLaEvidenciaQuedaEnElSeguimientoYLaGuiaNoSeToca() {
        Guia guia = guiaDeSamex(311171);
        registrar(guia, envio("999900000014", ENTREGA, "311171"));

        EvidenciaGuardada guardada = service.guardarEvidencia(SAMEX, "999900000014", PDF);

        assertTrue(guardada.evidenciaUrl().matches("/api/fotos/guia-" + guia.getId() + "-[0-9a-f-]{36}\\.pdf"),
                guardada.evidenciaUrl());
        assertFalse(guardada.guiaCerrada());
        EnvioExterno seguimiento = seguimientoDe(guia);
        assertEquals(guardada.evidenciaUrl(), seguimiento.getEvidenciaUrl());
        assertEquals(guardada.evidenciaHash(), seguimiento.getEvidenciaHash());
        Guia sinTocar = guiaRepository.findById(guia.getId()).orElseThrow();
        assertEquals(EstadoGuia.PENDIENTE, sinTocar.getEstado());
        assertNull(sinTocar.getUrlFoto());
        // Ya tiene evidencia: la corrida siguiente no la vuelve a pedir.
        assertTrue(registrar(guia, envio("999900000014", ENTREGA, "311171")).isEmpty());
    }

    @Test
    void mandarLaEvidenciaDosVecesNoGuardaOtroArchivo() {
        Guia guia = guiaDeSamex(311181);
        registrar(guia, envio("999900000015", ENTREGA, "311181"));
        EvidenciaGuardada primera = service.guardarEvidencia(SAMEX, "999900000015", PDF);

        EvidenciaGuardada segunda = service.guardarEvidencia(SAMEX, "999900000015", PDF);

        assertEquals(primera.evidenciaUrl(), segunda.evidenciaUrl());
        assertEquals(primera.evidenciaHash(), segunda.evidenciaHash());
    }

    @Test
    void unaImagenComoEvidenciaEs400YNoQuedaNada() {
        Guia guia = guiaDeSamex(311211);
        registrar(guia, envio("999900000016", ENTREGA, "311211"));

        ApiException e = assertThrows(ApiException.class,
                () -> service.guardarEvidencia(SAMEX, "999900000016", AlmacenamientoFotosTest.JPEG));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertNull(seguimientoDe(guia).getEvidenciaUrl());
    }

    @Test
    void laEvidenciaDeUnEnvioQueNoEsDeNingunaGuiaEs404() {
        ApiException e = assertThrows(ApiException.class,
                () -> service.guardarEvidencia(SAMEX, "999999999999", PDF));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void laEvidenciaDeUnEnvioQueTodaviaNoSeEntregaEs409() {
        Guia guia = guiaDeSamex(311221);
        registrar(guia, envio("999900000017", null, "311221"));

        ApiException e = assertThrows(ApiException.class,
                () -> service.guardarEvidencia(SAMEX, "999900000017", PDF));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertNull(seguimientoDe(guia).getEvidenciaUrl());
    }

    @Test
    void enModoCerrarLaEvidenciaCierraLaGuiaConLaHoraDeSamex() {
        EnviosExternosService cerrar = conModo("cerrar");
        Guia guia = guiaDeSamex(311191);
        cerrar.registrar(SAMEX, List.of(new ResultadoBusqueda(guia.getId(), guia.getFolio(),
                List.of(envio("999900000018", ENTREGA, "311191")))));

        EvidenciaGuardada guardada = cerrar.guardarEvidencia(SAMEX, "999900000018", PDF);

        assertTrue(guardada.guiaCerrada());
        Guia cerrada = guiaRepository.findById(guia.getId()).orElseThrow();
        assertEquals(EstadoGuia.ENTREGADA, cerrada.getEstado());
        assertEquals(ENTREGA, cerrada.getFechaEntrega());
        assertEquals(guardada.evidenciaUrl(), cerrada.getUrlFoto());
        assertEquals(guardada.evidenciaHash(), cerrada.getHashFoto());
        assertNull(cerrada.getCorreoEstado());
        // Cerrada, el lector ya no la busca.
        assertTrue(cerrar.pendientes(SAMEX).stream().noneMatch(p -> p.guiaId().equals(guia.getId())));
    }

    @Test
    void alPasarAModoCerrarSeCierranLasQueYaTeniaEvidencia() {
        // Es el paso de T-24: en la etapa sombra la evidencia se guardó sin cerrar la guía.
        Guia guia = guiaDeSamex(311201);
        registrar(guia, envio("999900000019", ENTREGA, "311201"));
        service.guardarEvidencia(SAMEX, "999900000019", PDF);
        assertEquals(EstadoGuia.PENDIENTE, guiaRepository.findById(guia.getId()).orElseThrow().getEstado());

        conModo("cerrar").registrar(SAMEX, List.of(new ResultadoBusqueda(guia.getId(), guia.getFolio(),
                List.of(envio("999900000019", ENTREGA, "311201")))));

        Guia cerrada = guiaRepository.findById(guia.getId()).orElseThrow();
        assertEquals(EstadoGuia.ENTREGADA, cerrada.getEstado());
        assertEquals(seguimientoDe(guia).getEvidenciaUrl(), cerrada.getUrlFoto());
    }

    @Test
    void unModoMalEscritoNoDejaArrancar() {
        // Un "cerar" no puede quedar en registrar sin que nadie se entere.
        assertThrows(IllegalArgumentException.class, () -> conModo("cerar"));
    }

    // --- bodega fija el número de envío a mano ---

    @Test
    void conElNumeroFijadoAManoElLectorBuscaPorNumeroYNoExigeElFolio() {
        // Es para cuando el folio no quedó bien escrito en Samex: bodega mira el portal y anota
        // el envío. Exigir el folio entre las referencias lo dejaría en conflicto para siempre.
        Guia guia = guiaDeSamex(311231);
        service.fijarNumero(guia.getId(), " 999900000020 ");

        assertEquals(List.of(new Pendiente(guia.getId(), 311231L, "999900000020")), service.pendientes(SAMEX));
        List<String> necesitanEvidencia = registrar(guia, envio("999900000020", ENTREGA, "CLIENTE FINAL", "311213"));

        EnvioExterno seguimiento = seguimientoDe(guia);
        assertEquals(ResultadoEnvio.ASOCIADO, seguimiento.getResultado());
        assertTrue(seguimiento.isFijadoAMano());
        assertEquals(List.of("999900000020"), necesitanEvidencia);
    }

    @Test
    void unNumeroFijadoSeSigueBuscandoAunqueSamexNoLoEncuentreUnaVez() {
        Guia guia = guiaDeSamex(311241);
        service.fijarNumero(guia.getId(), "999900000021");

        registrar(guia);

        assertEquals(ResultadoEnvio.NO_ENCONTRADO, seguimientoDe(guia).getResultado());
        assertEquals("999900000021", service.pendientes(SAMEX).get(0).envio());
    }

    @Test
    void quitarElNumeroFijadoVuelveABuscarPorElFolio() {
        Guia guia = guiaDeSamex(311251);
        service.fijarNumero(guia.getId(), "999900000022");

        service.fijarNumero(guia.getId(), null);

        EnvioExterno seguimiento = seguimientoDe(guia);
        assertFalse(seguimiento.isFijadoAMano());
        assertNull(seguimiento.getNumero());
        assertNull(service.pendientes(SAMEX).get(0).envio());
    }

    @Test
    void noSeFijaUnNumeroQueYaEsDeOtraGuia() {
        Guia primera = guiaDeSamex(311261);
        Guia segunda = guiaDeSamex(311262);
        registrar(primera, envio("999900000023", null, "311261"));

        ApiException e = assertThrows(ApiException.class, () -> service.fijarNumero(segunda.getId(), "999900000023"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertTrue(e.getMessage().contains("311261"), e.getMessage());
    }

    @Test
    void soloSeFijaElNumeroDeUnaGuiaPendienteQueVaPorUnTransportista() {
        Guia propia = guiaRepository.saveAndFlush(new Guia(docEntry++, 311271L, "Cliente", "Direccion"));
        Guia entregada = guiaDeSamex(311272);
        entregada.setEstado(EstadoGuia.ENTREGADA);
        guiaRepository.saveAndFlush(entregada);

        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.fijarNumero(propia.getId(), "999900000024")).getStatus());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.fijarNumero(entregada.getId(), "999900000024")).getStatus());
    }

    @Test
    void unNumeroConCaracteresRarosEs400() {
        Guia guia = guiaDeSamex(311281);

        ApiException e = assertThrows(ApiException.class, () -> service.fijarNumero(guia.getId(), "9999 0000; DROP"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    // --- lo que ve bodega ---

    @Test
    void laVistaDeBodegaCuentaLasPorBuscarYMarcaLasQueHayQueRevisar() {
        Guia sinEncontrar = guiaDeSamex(311291);
        Guia enConflicto = guiaDeSamex(311292);
        Guia asociada = guiaDeSamex(311293);
        registrar(sinEncontrar);
        registrar(enConflicto, envio("999900000025", null, "311292"), envio("999900000026", null, "311292"));
        registrar(asociada, envio("999900000027", null, "311293"));

        // Una semana después: la no encontrada ya lleva más de 2 días hábiles.
        EnviosExternosService.VistaBodega vista = service.vistaBodega(SAMEX, Instant.now().plusSeconds(7 * 86400));

        assertEquals(3, vista.porBuscar());
        assertEquals(3, vista.envios().size());
        assertTrue(revisar(vista, sinEncontrar));
        assertTrue(revisar(vista, enConflicto));
        assertFalse(revisar(vista, asociada));
        assertEquals(vista.envios().stream().map(EnviosExternosService.SeguimientoVista::ultimaBusqueda)
                .max(Instant::compareTo).orElseThrow(), vista.ultimaBusqueda());
    }

    private static boolean revisar(EnviosExternosService.VistaBodega vista, Guia guia) {
        return vista.envios().stream().filter(e -> e.guiaId().equals(guia.getId())).findFirst().orElseThrow().revisar();
    }

    @Test
    void unaGuiaQueYaSeCerroNoSeRevisaAunqueHayaQuedadoEnConflicto() {
        Guia guia = guiaDeSamex(311301);
        registrar(guia, envio("999900000028", null, "311301"), envio("999900000029", null, "311301"));
        guia.setEstado(EstadoGuia.ENTREGADA);
        guiaRepository.saveAndFlush(guia);

        EnviosExternosService.VistaBodega vista = service.vistaBodega(SAMEX, Instant.now());

        assertFalse(revisar(vista, guia));
        assertEquals(0, vista.porBuscar());
    }

    /** Lunes 23-11-2026 a las 10:00 en Chile. */
    private static final java.time.ZonedDateTime LUNES =
            java.time.ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, java.time.ZoneId.of("America/Santiago"));

    @Test
    void noEncontradaPasaARevisarRecienDespuesDeDosDiasHabiles() {
        Instant primera = LUNES.toInstant();

        assertFalse(EnviosExternosService.requiereRevision(ResultadoEnvio.NO_ENCONTRADO, primera,
                LUNES.plusDays(1).toInstant()));
        assertTrue(EnviosExternosService.requiereRevision(ResultadoEnvio.NO_ENCONTRADO, primera,
                LUNES.plusDays(2).toInstant()));
    }

    @Test
    void elFinDeSemanaNoCuentaComoDiaHabil() {
        Instant viernes = LUNES.plusDays(4).toInstant();

        // Sábado, domingo y lunes: un solo día hábil.
        assertFalse(EnviosExternosService.requiereRevision(ResultadoEnvio.NO_ENCONTRADO, viernes,
                LUNES.plusDays(7).toInstant()));
        assertTrue(EnviosExternosService.requiereRevision(ResultadoEnvio.NO_ENCONTRADO, viernes,
                LUNES.plusDays(8).toInstant()));
    }

    @Test
    void unConflictoSeRevisaDeInmediatoYUnaAsociadaNunca() {
        Instant ahora = LUNES.toInstant();

        assertTrue(EnviosExternosService.requiereRevision(ResultadoEnvio.CONFLICTO, ahora, ahora));
        assertFalse(EnviosExternosService.requiereRevision(ResultadoEnvio.ASOCIADO, ahora,
                LUNES.plusDays(30).toInstant()));
    }

    // --- lo que garantiza la base ---

    @Test
    void laBaseNoAdmiteDosSeguimientosParaLaMismaGuia() {
        Guia guia = guiaDeSamex(311151);
        envioRepository.saveAndFlush(new EnvioExterno(guia.getId(), SAMEX, guia.getFolio(), Instant.now()));

        assertThrows(DataIntegrityViolationException.class, () -> envioRepository.saveAndFlush(
                new EnvioExterno(guia.getId(), SAMEX, guia.getFolio(), Instant.now())));
    }
}
