package com.calimport.guias.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.EnvioExterno;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.ResultadoEnvio;
import com.calimport.guias.repository.EnvioExternoRepository;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.service.AlmacenamientoFotos.FotoGuardada;
import com.calimport.guias.utils.ApiException;

/**
 * El seguimiento de las guías que lleva un transportista externo (SAMEX), contado por el lector
 * de su portal (el job {@code samex-sync}, que no tiene API).
 *
 * <p>El lector parte de las guías, no del portal: pide las {@link #pendientes}, busca cada una
 * por su folio (o por su número de envío, si ya se confirmó) y devuelve lo que encontró con
 * {@link #registrar}. Acá se decide si ese envío es el de la guía, con reglas que prefieren dejar
 * una guía sin asociar antes que asociarle el envío de otra: cerrarla con el PDF de otro cliente
 * no se deshace.
 *
 * <p>Escribe el seguimiento ({@link EnvioExterno}) y guarda el PDF de la entrega. La guía la entrega
 * {@link GuiaService#entregarPorTransportista}, y solo en modo {@code cerrar}
 * ({@code GUIAS_SAMEX_MODO}): en {@code registrar}, el valor por defecto, todo queda anotado sin
 * tocar la guía.
 */
@Service
public class EnviosExternosService {

    private static final Logger log = LoggerFactory.getLogger(EnviosExternosService.class);

    private final GuiaRepository guiaRepository;
    private final EnvioExternoRepository envioRepository;
    private final GuiaService guiaService;
    private final AlmacenamientoFotos almacenamiento;
    private final boolean cierraGuias;

    /**
     * @param modo {@code guias.samex.modo}: {@code registrar} (el valor por defecto) guarda lo que
     *     dice el transportista y su PDF sin tocar la guía, que es la etapa sombra para medir el
     *     cruce con datos reales; {@code cerrar} además entrega la guía. Otro valor no arranca: un
     *     "cerar" mal escrito no puede quedar en registrar sin que nadie se entere.
     */
    public EnviosExternosService(GuiaRepository guiaRepository, EnvioExternoRepository envioRepository,
                                 GuiaService guiaService, AlmacenamientoFotos almacenamiento,
                                 @Value("${guias.samex.modo:registrar}") String modo) {
        this.guiaRepository = guiaRepository;
        this.envioRepository = envioRepository;
        this.guiaService = guiaService;
        this.almacenamiento = almacenamiento;
        this.cierraGuias = switch (modo == null ? "" : modo.strip().toLowerCase(Locale.ROOT)) {
            case "registrar" -> false;
            case "cerrar" -> true;
            default -> throw new IllegalArgumentException(
                    "GUIAS_SAMEX_MODO tiene que ser 'registrar' o 'cerrar', no '" + modo + "'");
        };
        log.info("Integracion con transportistas externos en modo {}", cierraGuias ? "cerrar" : "registrar");
    }

    /**
     * Una guía que el lector tiene que buscar.
     *
     * @param envio el número de envío ya confirmado, para buscarla por número (exacto) en vez de
     *     por referencia; null mientras no lo haya.
     */
    public record Pendiente(Long guiaId, Long folio, String envio) {
    }

    /**
     * Un envío que el lector encontró en el portal.
     *
     * @param estadoPortal lo que dice el portal, tal cual ("Entregada movilidad 23/11/2026").
     * @param referencias las del detalle del envío; entre ellas tiene que estar el folio.
     * @param fechaEntrega cuándo lo entregó, según el portal; null si todavía no se entrega.
     */
    public record EnvioEncontrado(String numero, String estadoPortal, List<String> referencias, Instant fechaEntrega) {
    }

    /** Lo que encontró el lector al buscar una guía: ningún envío, uno o varios. */
    public record ResultadoBusqueda(Long guiaId, Long folio, List<EnvioEncontrado> envios) {
    }

    /**
     * El PDF quedó guardado ({@code /api/fotos/...pdf}, con su SHA-256).
     *
     * @param guiaCerrada si la guía quedó entregada con este PDF (solo en modo {@code cerrar}).
     */
    public record EvidenciaGuardada(String evidenciaUrl, String evidenciaHash, boolean guiaCerrada) {
    }

    /**
     * Las guías que el lector tiene que buscar: las que lleva este transportista y siguen
     * pendientes. Primero las que nunca se buscaron y después la de búsqueda más antigua, para que
     * con un tope por corrida ninguna quede siempre al final.
     */
    @Transactional(readOnly = true)
    public List<Pendiente> pendientes(String transportista) {
        List<Guia> guias = guiaRepository.findByTransportistaAndEstado(transportista, EstadoGuia.PENDIENTE);
        Map<Long, EnvioExterno> seguimientos = envioRepository
                .findByGuiaIdIn(guias.stream().map(Guia::getId).toList()).stream()
                .collect(Collectors.toMap(EnvioExterno::getGuiaId, Function.identity()));

        Comparator<Guia> porBusqueda = Comparator.comparing(
                (Guia g) -> Optional.ofNullable(seguimientos.get(g.getId())).map(EnvioExterno::getUltimaBusqueda).orElse(null),
                Comparator.nullsFirst(Comparator.naturalOrder()));
        return guias.stream()
                .sorted(porBusqueda.thenComparing(Guia::getId))
                .map(g -> new Pendiente(g.getId(), g.getFolio(), numeroConfirmado(seguimientos.get(g.getId()))))
                .toList();
    }

    /**
     * El número por el que conviene buscar: el confirmado, o el que fijó bodega a mano aunque la
     * última búsqueda no lo haya encontrado (no se vuelve a buscar por un folio que está mal).
     */
    private static String numeroConfirmado(EnvioExterno seguimiento) {
        if (seguimiento == null) {
            return null;
        }
        return seguimiento.getResultado() == ResultadoEnvio.ASOCIADO || seguimiento.isFijadoAMano()
                ? seguimiento.getNumero() : null;
    }

    /**
     * Guarda lo que encontró el lector y devuelve los números de envío entregados cuyo PDF falta:
     * el lector los baja y los sube aparte.
     *
     * <p>Se ignora el resultado de una guía que ya no es de este transportista, que ya se resolvió
     * o cuyo folio no es el que se buscó: entre que el lector pidió las pendientes y responde,
     * bodega pudo cambiarla, y una guía resuelta no se toca nunca.
     */
    @Transactional
    public List<String> registrar(String transportista, List<ResultadoBusqueda> resultados) {
        List<String> necesitanEvidencia = new ArrayList<>();
        Instant ahora = Instant.now();
        for (ResultadoBusqueda resultado : resultados) {
            // Llega por HTTP: un elemento sin guía se descarta sin llevarse por delante el resto.
            Guia guia = resultado.guiaId() == null ? null : guiaRepository.findById(resultado.guiaId()).orElse(null);
            if (!sigueSiendoDe(guia, transportista, resultado)) {
                log.info("Resultado de {} para la guia {} (folio {}) ignorado: ya no es de ese transportista, "
                        + "se resolvio o el folio no coincide", transportista, resultado.guiaId(), resultado.folio());
                continue;
            }
            EnvioExterno seguimiento = envioRepository.findByGuiaId(guia.getId())
                    .orElseGet(() -> new EnvioExterno(guia.getId(), transportista, guia.getFolio(), ahora));
            seguimiento.setUltimaBusqueda(ahora);
            aplicar(seguimiento, guia.getFolio(), transportista,
                    resultado.envios() == null ? List.of() : resultado.envios());
            envioRepository.save(seguimiento);

            if (seguimiento.getResultado() == ResultadoEnvio.ASOCIADO && seguimiento.getFechaEntrega() != null
                    && seguimiento.getEvidenciaUrl() == null) {
                necesitanEvidencia.add(seguimiento.getNumero());
            }
            // La que ya tenía su PDF de antes (de la etapa sombra, en modo registrar) se cierra en
            // la primera corrida después de pasar a modo cerrar, sin volver a bajarlo.
            cerrarSiCorresponde(seguimiento);
        }
        return necesitanEvidencia;
    }

    /**
     * Guarda el PDF de la entrega de un envío asociado y, en modo {@code cerrar}, entrega la guía.
     *
     * <p>El PDF es la evidencia y no se reemplaza: si el envío ya tiene uno, se responde con ése
     * sin guardar otro archivo, así reintentar la subida es inofensivo. Que el PDF sea de ese envío
     * (la página 1 nombra la expedición) lo comprueba el lector antes de subirlo; acá se valida que
     * sea un PDF y su tamaño.
     */
    @Transactional
    public EvidenciaGuardada guardarEvidencia(String transportista, String numero, byte[] pdf) {
        EnvioExterno seguimiento = envioRepository.findByTransportistaAndNumero(transportista, numero)
                .filter(s -> s.getResultado() == ResultadoEnvio.ASOCIADO)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No hay una guía asociada al envío " + numero + " de " + transportista));
        if (seguimiento.getFechaEntrega() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "El envío " + numero + " de " + transportista + " todavía no está entregado");
        }
        if (seguimiento.getEvidenciaUrl() == null) {
            FotoGuardada guardada = almacenamiento.guardarPdf(seguimiento.getGuiaId(), pdf);
            seguimiento.setEvidenciaUrl(guardada.urlFoto());
            seguimiento.setEvidenciaHash(guardada.hashFoto());
            envioRepository.save(seguimiento);
        }
        cerrarSiCorresponde(seguimiento);
        boolean cerrada = guiaRepository.findById(seguimiento.getGuiaId())
                .map(g -> g.getEstado() == EstadoGuia.ENTREGADA && seguimiento.getEvidenciaUrl().equals(g.getUrlFoto()))
                .orElse(false);
        return new EvidenciaGuardada(seguimiento.getEvidenciaUrl(), seguimiento.getEvidenciaHash(), cerrada);
    }

    /**
     * Bodega fija a mano el número de envío de una guía, cuando el folio no quedó bien escrito en
     * el portal y la búsqueda no la encuentra (o encuentra otra cosa). Desde ahí el lector la busca
     * por ese número. Sin número ({@code null} o vacío) deshace lo fijado y se vuelve a buscar por
     * el folio.
     */
    @Transactional
    public EnvioExterno fijarNumero(Long guiaId, String numero) {
        Guia guia = guiaRepository.findById(guiaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No existe una guía con id " + guiaId));
        if (guia.getEstado() != EstadoGuia.PENDIENTE || guia.getTransportista() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Solo se fija el envío de una guía pendiente que lleva un transportista externo");
        }
        String transportista = guia.getTransportista();
        EnvioExterno seguimiento = envioRepository.findByGuiaId(guiaId)
                .orElseGet(() -> new EnvioExterno(guiaId, transportista, guia.getFolio(), Instant.now()));
        String limpio = numero == null ? "" : numero.strip();

        if (limpio.isEmpty()) {
            seguimiento.setFijadoAMano(false);
            seguimiento.setNumero(null);
            seguimiento.setResultado(ResultadoEnvio.NO_ENCONTRADO);
            seguimiento.setMotivo("Bodega quitó el número de envío: se vuelve a buscar por el folio");
            return envioRepository.save(seguimiento);
        }
        // Los números de Samex son dígitos; se admiten letras y guion por si otro transportista
        // los usa, pero nada que pueda romper una búsqueda en su portal.
        if (!limpio.matches("[A-Za-z0-9-]{1,30}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El número de envío solo puede tener letras, dígitos y guion");
        }
        Optional<EnvioExterno> deOtra = envioRepository.findByTransportistaAndNumero(transportista, limpio)
                .filter(otro -> !otro.getGuiaId().equals(guiaId));
        if (deOtra.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "El envío " + limpio + " ya es de la guía folio " + deOtra.get().getFolio());
        }
        seguimiento.setNumero(limpio);
        seguimiento.setFijadoAMano(true);
        seguimiento.setResultado(ResultadoEnvio.ASOCIADO);
        seguimiento.setMotivo(null);
        return envioRepository.save(seguimiento);
    }

    // --- lo que ve bodega ---

    /** El seguimiento de una guía tal como lo muestra la tabla de bodega. */
    public record SeguimientoVista(Long guiaId, Long folio, String transportista, String numero,
                                   ResultadoEnvio resultado, String motivo, String estadoPortal,
                                   Instant fechaEntrega, boolean conEvidencia, boolean fijadoAMano,
                                   Instant primeraBusqueda, Instant ultimaBusqueda, boolean revisar) {
    }

    /**
     * @param ultimaBusqueda la última vez que el lector buscó alguna guía; null si nunca. Con
     *     {@code porBuscar} en 0 no hay nada que leer, y una fecha vieja no es señal de nada.
     * @param porBuscar las guías marcadas con este transportista que siguen pendientes.
     */
    public record VistaBodega(Instant ultimaBusqueda, int porBuscar, List<SeguimientoVista> envios) {
    }

    @Transactional(readOnly = true)
    public VistaBodega vistaBodega(String transportista, Instant ahora) {
        java.util.Set<Long> porBuscar = guiaRepository.findByTransportistaAndEstado(transportista, EstadoGuia.PENDIENTE)
                .stream().map(Guia::getId).collect(Collectors.toSet());
        List<SeguimientoVista> envios = envioRepository.findByTransportista(transportista).stream()
                .map(s -> new SeguimientoVista(s.getGuiaId(), s.getFolio(), s.getTransportista(), s.getNumero(),
                        s.getResultado(), s.getMotivo(), s.getEstadoPortal(), s.getFechaEntrega(),
                        s.getEvidenciaUrl() != null, s.isFijadoAMano(), s.getPrimeraBusqueda(), s.getUltimaBusqueda(),
                        // Solo se revisa lo que sigue pendiente: una guía cerrada ya no tiene nada que resolver.
                        porBuscar.contains(s.getGuiaId())
                                && requiereRevision(s.getResultado(), s.getPrimeraBusqueda(), ahora)))
                .toList();
        Instant ultima = envios.stream().map(SeguimientoVista::ultimaBusqueda).max(Instant::compareTo).orElse(null);
        return new VistaBodega(ultima, porBuscar.size(), envios);
    }

    /** Los días hábiles se cuentan en Chile, donde trabajan bodega y Samex. */
    private static final ZoneId ZONA_CHILE = ZoneId.of("America/Santiago");

    /**
     * Si bodega tiene que mirar este seguimiento: un conflicto, siempre; una guía que el transportista
     * no encuentra, recién después de 2 días hábiles, porque recién marcada es lo normal (el envío
     * puede crearse en el portal después). Los feriados no se descuentan.
     */
    static boolean requiereRevision(ResultadoEnvio resultado, Instant primeraBusqueda, Instant ahora) {
        return switch (resultado) {
            case CONFLICTO -> true;
            case NO_ENCONTRADO -> diasHabilesEntre(primeraBusqueda, ahora) >= 2;
            case ASOCIADO -> false;
        };
    }

    /** Los días de lunes a viernes después del día de {@code desde}, hasta el de {@code hasta} incluido. */
    static int diasHabilesEntre(Instant desde, Instant hasta) {
        LocalDate dia = desde.atZone(ZONA_CHILE).toLocalDate().plusDays(1);
        LocalDate fin = hasta.atZone(ZONA_CHILE).toLocalDate();
        int habiles = 0;
        for (; !dia.isAfter(fin); dia = dia.plusDays(1)) {
            if (dia.getDayOfWeek() != DayOfWeek.SATURDAY && dia.getDayOfWeek() != DayOfWeek.SUNDAY) {
                habiles++;
            }
        }
        return habiles;
    }

    /**
     * En modo {@code cerrar}, entrega la guía de un envío asociado que ya tiene fecha de entrega y
     * PDF, si sigue pendiente y marcada para este transportista. En modo {@code registrar} no hace
     * nada: es la etapa sombra.
     */
    private void cerrarSiCorresponde(EnvioExterno seguimiento) {
        if (!cierraGuias || seguimiento.getResultado() != ResultadoEnvio.ASOCIADO
                || seguimiento.getFechaEntrega() == null || seguimiento.getEvidenciaUrl() == null) {
            return;
        }
        Guia guia = guiaRepository.findById(seguimiento.getGuiaId()).orElse(null);
        if (guia == null || guia.getEstado() != EstadoGuia.PENDIENTE
                || !seguimiento.getTransportista().equals(guia.getTransportista())) {
            return;
        }
        guiaService.entregarPorTransportista(guia.getId(), seguimiento.getTransportista(),
                seguimiento.getEvidenciaUrl(), seguimiento.getEvidenciaHash(), seguimiento.getFechaEntrega());
        log.info("Guia folio {} entregada por {} (envio {})", guia.getFolio(), seguimiento.getTransportista(),
                seguimiento.getNumero());
    }

    private static boolean sigueSiendoDe(Guia guia, String transportista, ResultadoBusqueda resultado) {
        return guia != null
                && guia.getEstado() == EstadoGuia.PENDIENTE
                && transportista.equals(guia.getTransportista())
                && guia.getFolio().equals(resultado.folio());
    }

    /**
     * Decide si lo encontrado es el envío de la guía. Solo queda asociado un envío único que
     * tenga el folio entre sus referencias y que no sea ya de otra guía. En cualquier otro caso
     * el seguimiento dice por qué y conserva lo que tenía: la guía queda para bodega.
     */
    private void aplicar(EnvioExterno seguimiento, Long folio, String transportista, List<EnvioEncontrado> envios) {
        if (envios.isEmpty()) {
            noAsociado(seguimiento, ResultadoEnvio.NO_ENCONTRADO,
                    transportista + " no tiene un envío con el folio " + folio);
            return;
        }
        if (envios.size() > 1) {
            noAsociado(seguimiento, ResultadoEnvio.CONFLICTO, transportista + " tiene varios envíos con el folio "
                    + folio + ": " + envios.stream().map(EnvioEncontrado::numero).collect(Collectors.joining(", ")));
            return;
        }
        EnvioEncontrado envio = envios.get(0);
        String numero = envio.numero() == null ? "" : envio.numero().strip();
        List<String> referencias = envio.referencias() == null ? List.of() : envio.referencias();
        if (numero.isEmpty()) {
            noAsociado(seguimiento, ResultadoEnvio.CONFLICTO, transportista + " devolvió un envío sin número");
            return;
        }
        // Si bodega fijó este número a mano es porque el folio no quedó bien en el portal: lo
        // garantizó una persona, y exigirlo lo dejaría en conflicto para siempre.
        boolean garantizadoPorBodega = seguimiento.isFijadoAMano() && numero.equals(seguimiento.getNumero());
        if (!garantizadoPorBodega && !tieneElFolio(referencias, folio)) {
            // La búsqueda por referencia del portal también trae coincidencias parciales.
            noAsociado(seguimiento, ResultadoEnvio.CONFLICTO, "El envío " + numero + " de " + transportista
                    + " no tiene el folio " + folio + " entre sus referencias (tiene: "
                    + String.join(", ", referencias) + ")");
            return;
        }
        Optional<EnvioExterno> deOtra = envioRepository.findByTransportistaAndNumero(transportista, numero)
                .filter(otro -> !otro.getGuiaId().equals(seguimiento.getGuiaId()));
        if (deOtra.isPresent()) {
            noAsociado(seguimiento, ResultadoEnvio.CONFLICTO, "El envío " + numero + " de " + transportista
                    + " ya es de la guía folio " + deOtra.get().getFolio());
            return;
        }

        seguimiento.setResultado(ResultadoEnvio.ASOCIADO);
        seguimiento.setMotivo(null);
        seguimiento.setNumero(numero);
        seguimiento.setEstadoPortal(recortar(envio.estadoPortal(), 200));
        seguimiento.setReferencias(recortar(String.join(", ", referencias), 500));
        seguimiento.setFechaEntrega(envio.fechaEntrega());
    }

    private static void noAsociado(EnvioExterno seguimiento, ResultadoEnvio resultado, String motivo) {
        seguimiento.setResultado(resultado);
        seguimiento.setMotivo(recortar(motivo, 500));
    }

    /**
     * Si alguna referencia es el folio. Se comparan como números porque el portal puede guardarlo
     * con ceros adelante ("0311994"); una referencia con letras no cuenta, aunque las contenga.
     */
    static boolean tieneElFolio(List<String> referencias, long folio) {
        return referencias.stream()
                .map(r -> r == null ? "" : r.strip())
                .filter(r -> r.matches("\\d{1,18}"))
                .anyMatch(r -> Long.parseLong(r) == folio);
    }

    /** Un texto del portal más largo que su columna haría fallar la corrida entera: se corta. */
    private static String recortar(String texto, int largo) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.length() <= largo ? limpio : limpio.substring(0, largo);
    }
}
