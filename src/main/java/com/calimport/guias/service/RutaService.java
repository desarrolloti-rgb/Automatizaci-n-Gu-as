package com.calimport.guias.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import com.calimport.guias.config.RutasConfig;
import com.calimport.guias.controller.dto.GenerarRutaRequest;
import com.calimport.guias.controller.dto.GenerarRutaRequest.GuiaEnRuta;
import com.calimport.guias.controller.dto.OdometroRequest;
import com.calimport.guias.controller.dto.RutaResponse;
import com.calimport.guias.controller.dto.RutaResumen;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.EstadoRuta;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.Repartidor;
import com.calimport.guias.model.Rol;
import com.calimport.guias.model.Ruta;
import com.calimport.guias.model.RutaParada;
import com.calimport.guias.repository.RutaRepository;
import com.calimport.guias.service.Geocodificador.Ubicacion;
import com.calimport.guias.service.InterpreteComentarios.Interpretacion;
import com.calimport.guias.service.LinksNavegacion.Punto;
import com.calimport.guias.service.OptimizadorRutas.Plan;
import com.calimport.guias.service.OptimizadorRutas.Visita;
import com.calimport.guias.utils.ApiException;

/**
 * Arma la ruta del día de un repartidor a partir de las guías que le asigna el bodeguero.
 *
 * <p>El orden de los pasos importa. Todo lo que puede fallar por causas externas
 * (geocodificar, interpretar comentarios, optimizar) corre <b>antes</b> de asignar: si un
 * servicio no responde, las guías quedan como estaban y el bodeguero simplemente reintenta.
 * Lo que se calcula en el camino (coordenadas, horario interpretado) sí se guarda enseguida,
 * porque es válido aunque la ruta no llegue a generarse y así no se consulta dos veces.
 *
 * <p>Cada paso tiene una implementación gratuita (la de por defecto) y una de Google: ver
 * {@link Geocodificador}, {@link InterpreteComentarios} y {@link OptimizadorRutas}.
 */
@Service
public class RutaService {

    private static final Logger log = LoggerFactory.getLogger(RutaService.class);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Más que esto entre la salida y el término es un número mal escrito (un dígito de más):
     * ninguna ruta de reparto en Santiago se acerca. Se rechaza para que lo corrija en el momento.
     */
    static final int MAX_KM_POR_DIA = 500;

    private final GuiaService guiaService;
    private final RepartidorService repartidorService;
    private final RutaRepository rutaRepository;
    private final Geocodificador geocodificador;
    private final InterpreteComentarios interpreteComentarios;
    private final OptimizadorRutas optimizadorRutas;
    private final RutasConfig config;
    private final TransactionOperations transacciones;

    public RutaService(GuiaService guiaService, RepartidorService repartidorService, RutaRepository rutaRepository,
                       Geocodificador geocodificador, InterpreteComentarios interpreteComentarios,
                       OptimizadorRutas optimizadorRutas, RutasConfig config, TransactionOperations transacciones) {
        this.guiaService = guiaService;
        this.repartidorService = repartidorService;
        this.rutaRepository = rutaRepository;
        this.geocodificador = geocodificador;
        this.interpreteComentarios = interpreteComentarios;
        this.optimizadorRutas = optimizadorRutas;
        this.config = config;
        this.transacciones = transacciones;
    }

    public RutaResponse generar(GenerarRutaRequest request) {
        if (!config.origenConfigurado()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Falta configurar la ubicación de la bodega (RUTAS_ORIGEN_LAT / RUTAS_ORIGEN_LNG)");
        }
        Repartidor repartidor = repartidorService.obtenerPorId(request.repartidorId());
        if (!repartidor.isActivo()) {
            throw new ApiException(HttpStatus.CONFLICT, "El repartidor " + repartidor.getNombre() + " no está activo");
        }
        if (repartidor.getRol() != Rol.Despachador) {
            throw new ApiException(HttpStatus.CONFLICT, repartidor.getNombre() + " no es repartidor");
        }

        List<Guia> guias = interpretarComentarios(ubicar(cargarGuias(request.guias())));
        boolean vuelveABodega = request.vuelveABodega() != null ? request.vuelveABodega() : repartidor.isVuelveABodega();

        Plan plan;
        try {
            plan = optimizadorRutas.optimizar(request.fecha(), request.horaSalida(), vuelveABodega, guias);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Error optimizando la ruta del repartidor {}: {}", request.repartidorId(), e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error calculando el orden de la ruta");
        }

        return transacciones.execute(estado -> guardar(request, vuelveABodega, guias, plan));
    }

    @Transactional(readOnly = true)
    public RutaResponse obtener(Integer repartidorId, LocalDate fecha) {
        Ruta ruta = rutaRepository.findByRepartidorIdAndFecha(repartidorId, fecha)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No hay ruta para el repartidor " + repartidorId + " el " + fecha));
        return aRespuesta(ruta);
    }

    /** Los días del historial que se piden de una vez: tres meses. */
    static final int MAX_DIAS_HISTORIAL = 93;

    /**
     * El historial de rutas: una fila por ruta, las más nuevas primero, con lo que bodega mide
     * sin abrirla (estado, paradas por resultado, km planificados y odómetro). Las paradas se
     * cuentan en una consulta agrupada: cargarlas traería cada guía.
     *
     * @param repartidorId null = todos
     */
    @Transactional(readOnly = true)
    public List<RutaResumen> historial(LocalDate desde, LocalDate hasta, Integer repartidorId) {
        if (desde == null || hasta == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Indica desde y hasta qué fecha");
        }
        if (hasta.isBefore(desde)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La fecha hasta no puede ser anterior a la fecha desde");
        }
        if (ChronoUnit.DAYS.between(desde, hasta) >= MAX_DIAS_HISTORIAL) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Pide como máximo " + MAX_DIAS_HISTORIAL + " días de historial a la vez");
        }
        List<Ruta> rutas = repartidorId == null
                ? rutaRepository.findByFechaBetweenOrderByFechaDescRepartidorIdAsc(desde, hasta)
                : rutaRepository.findByRepartidorIdAndFechaBetweenOrderByFechaDesc(repartidorId, desde, hasta);
        if (rutas.isEmpty()) {
            return List.of();
        }

        Map<Long, Map<EstadoGuia, Long>> conteos = new HashMap<>();
        for (RutaRepository.ConteoParadas c : rutaRepository.contarParadas(rutas.stream().map(Ruta::getId).toList())) {
            // HashMap y no EnumMap: el resultado null (sin resolver) también es una clave.
            conteos.computeIfAbsent(c.getRutaId(), id -> new HashMap<>()).put(c.getResultado(), c.getCantidad());
        }
        // Todos, también los inactivos: el historial sigue nombrando a quien ya no reparte.
        Map<Integer, String> nombres = new HashMap<>();
        repartidorService.listar().forEach(r -> nombres.put(r.getEmployeeId(), r.getNombre()));

        LocalDate hoy = LocalDate.now(config.zona());
        return rutas.stream().map(ruta -> {
            Map<EstadoGuia, Long> c = conteos.getOrDefault(ruta.getId(), Map.of());
            int entregadas = c.getOrDefault(EstadoGuia.ENTREGADA, 0L).intValue();
            int rechazadas = c.getOrDefault(EstadoGuia.RECHAZADA, 0L).intValue();
            int pendientes = c.containsKey(null) ? c.get(null).intValue() : 0;
            int total = entregadas + rechazadas + pendientes;
            Integer salida = ruta.getOdometroSalida();
            Integer termino = ruta.getOdometroTermino();
            return new RutaResumen(ruta.getId(), ruta.getRepartidorId(),
                    nombres.getOrDefault(ruta.getRepartidorId(), "Repartidor " + ruta.getRepartidorId()),
                    ruta.getFecha(), ruta.getHoraSalida(),
                    EstadoRuta.de(total, entregadas + rechazadas, ruta.isVuelveABodega(), salida != null,
                            termino != null, ruta.getFecha().isBefore(hoy)),
                    ruta.isVuelveABodega(), total, entregadas, rechazadas, pendientes,
                    ruta.getKmPlanificados() == null ? null : Math.round(ruta.getKmPlanificados() * 10) / 10.0,
                    salida, termino, salida != null && termino != null ? termino - salida : null);
        }).toList();
    }

    /**
     * La ruta de <b>hoy</b> en la zona de las rutas, no en la del servidor: la VM corre en
     * UTC y, entre las 21:00 y las 24:00 de Chile, su "hoy" ya es mañana.
     */
    @Transactional(readOnly = true)
    public RutaResponse obtenerDeHoy(Integer repartidorId) {
        return obtener(repartidorId, LocalDate.now(config.zona()));
    }

    /**
     * El repartidor anota lo que marca el odómetro al salir o al terminar, en su ruta de
     * <b>hoy</b>: la lectura vale como respaldo porque se anota en el momento, y cada una
     * guarda cuándo. Es el kilometraje real contra el que se compara el plan.
     */
    @Transactional
    public RutaResponse anotarOdometro(Integer repartidorId, OdometroRequest request) {
        if (request.salida() == null && request.termino() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Indica el kilometraje al salir o al terminar");
        }
        LocalDate hoy = LocalDate.now(config.zona());
        Ruta ruta = rutaRepository.findByRepartidorIdAndFecha(repartidorId, hoy)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No tienes ruta para hoy: el kilometraje se anota en la ruta que genera bodega"));

        // Se valida el par como quedaría, así una corrección de la salida también se revisa
        // contra el término ya anotado.
        Integer salida = request.salida() != null ? request.salida() : ruta.getOdometroSalida();
        Integer termino = request.termino() != null ? request.termino() : ruta.getOdometroTermino();
        if (salida != null && termino != null) {
            if (termino < salida) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "El kilometraje al terminar (" + km(termino)
                        + ") no puede ser menor que al salir (" + km(salida) + ")");
            }
            if (termino - salida > MAX_KM_POR_DIA) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Entre la salida (" + km(salida) + ") y el término ("
                        + km(termino) + ") hay " + km(termino - salida) + " km: revisa los números");
            }
        }

        Instant ahora = Instant.now();
        if (request.salida() != null) {
            ruta.anotarOdometroSalida(request.salida(), ahora);
        }
        if (request.termino() != null) {
            ruta.anotarOdometroTermino(request.termino(), ahora);
        }
        return aRespuesta(rutaRepository.save(ruta));
    }

    /** 12403 → "12.403", como se lee en el tablero. */
    private static String km(int km) {
        return String.format(Locale.ROOT, "%,d", km).replace(',', '.');
    }

    private List<Guia> cargarGuias(List<GuiaEnRuta> pedidas) {
        Set<Long> vistas = new HashSet<>();
        for (GuiaEnRuta pedida : pedidas) {
            if (!vistas.add(pedida.guiaId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "La guía " + pedida.guiaId() + " viene repetida");
            }
        }

        List<Guia> guias = new ArrayList<>();
        for (GuiaEnRuta pedida : pedidas) {
            Guia guia = guiaService.obtenerPorId(pedida.guiaId());
            if (guia.getEstado() != EstadoGuia.PENDIENTE) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "La guía folio " + guia.getFolio() + " ya fue resuelta (" + guia.getEstado() + ")");
            }
            // Se descarta acá, antes de ubicar y ordenar, para no gastar llamadas externas en
            // una ruta que no se va a poder asignar.
            if (guia.getTransportista() != null) {
                throw new ApiException(HttpStatus.CONFLICT, GuiaService.noEntraEnRuta(guia));
            }
            if (pedida.traeHorario()) {
                guia = guiaService.definirHorario(guia.getId(), pedida.ventanaDesde(), pedida.ventanaHasta(), pedida.nota());
            }
            guias.add(guia);
        }
        return guias;
    }

    private List<Guia> ubicar(List<Guia> guias) {
        List<Guia> ubicadas = new ArrayList<>();
        List<String> sinUbicar = new ArrayList<>();
        for (Guia guia : guias) {
            if (guia.tieneUbicacion()) {
                ubicadas.add(guia);
                continue;
            }
            Optional<Ubicacion> ubicacion;
            try {
                ubicacion = geocodificador.geocodificar(guia.getDireccion());
                if (ubicacion.isEmpty()) {
                    ubicacion = geocodificador.aproximar(guia.getDireccion());
                }
            } catch (RuntimeException e) {
                log.error("Error geocodificando la guia {}: {}", guia.getId(), e.getMessage(), e);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Error consultando el mapa para ubicar las direcciones");
            }
            if (ubicacion.isEmpty()) {
                sinUbicar.add(guia.getFolio() + " (" + guia.getDireccion() + ")");
                continue;
            }
            Ubicacion u = ubicacion.get();
            ubicadas.add(guiaService.guardarUbicacion(guia.getId(), u.latitud(), u.longitud(), u.aproximada()));
        }
        if (!sinUbicar.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "No se encontró en el mapa la dirección de las guías folio " + String.join(", ", sinUbicar)
                            + ", ni siquiera su comuna. Corregirla en la columna Dirección de la pestaña Guías.");
        }
        return ubicadas;
    }

    /**
     * Si la interpretación falla, la ruta se genera igual sin ese horario: es preferible una ruta que
     * ignore un comentario a no tener ruta. El comentario y el pie viajan completos en la
     * respuesta, así que el repartidor los ve de todas formas, y se reintenta en la próxima
     * generación.
     */
    private List<Guia> interpretarComentarios(List<Guia> guias) {
        List<Guia> resultado = new ArrayList<>();
        for (Guia guia : guias) {
            String texto = textoConElHorario(guia);
            if (guia.getOrigenHorario() != null || texto == null) {
                resultado.add(guia);
                continue;
            }
            try {
                Interpretacion i = interpreteComentarios.interpretar(texto);
                resultado.add(guiaService.guardarHorarioInterpretado(guia.getId(), i.desde(), i.hasta(), i.nota()));
            } catch (RuntimeException e) {
                log.warn("No se pudo interpretar el horario de la guia {}: {}", guia.getId(), e.getMessage());
                resultado.add(guia);
            }
        }
        return resultado;
    }

    /**
     * De dónde sacar la ventana de entrega. <b>El pie manda</b>: cuando el documento trae un
     * "HORARIO:", ése es el horario de este despacho, y además llega limpio — solo la frase
     * del horario, sin teléfonos ni direcciones que puedan leerse como una hora. El
     * {@code Comments} queda para las guías cuyo pie no dice nada de horario.
     */
    private static String textoConElHorario(Guia guia) {
        String delPie = guia.getHorarioFooter();
        return (delPie != null && !delPie.isBlank()) ? delPie : guia.getComentario();
    }

    private RutaResponse guardar(GenerarRutaRequest request, boolean vuelveABodega, List<Guia> guias, Plan plan) {
        List<Long> ids = guias.stream().map(Guia::getId).toList();
        // Se van las paradas de la ruta del día y las de estas guías en otras rutas: una guía
        // reasignada sale de la ruta donde estaba.
        rutaRepository.borrarParadasPrevias(ids, request.repartidorId(), request.fecha());

        // Regenerar replanifica la misma ruta en vez de borrarla: es lo que se mide, y lo que
        // ya se haya medido de ella no se puede perder. Se busca después del delete, que
        // limpia el contexto de persistencia: así llega sin las paradas borradas.
        Ruta ruta = rutaRepository.findByRepartidorIdAndFecha(request.repartidorId(), request.fecha())
                .orElseGet(() -> new Ruta(request.repartidorId(), request.fecha(), request.horaSalida()));
        ruta.planificar(request.horaSalida(), vuelveABodega, plan.kilometros(), plan.termino());
        int orden = 1;
        for (Visita visita : plan.visitas()) {
            Guia guia = guiaService.asignarRepartidor(visita.guiaId(), request.repartidorId());
            ruta.agregarParada(guia, orden++, visita.llegada());
        }
        RutaResponse respuesta = aRespuesta(rutaRepository.save(ruta));

        if (request.vuelveABodega() != null) {
            repartidorService.recordarVuelta(request.repartidorId(), vuelveABodega);
        }
        return respuesta;
    }

    RutaResponse aRespuesta(Ruta ruta) {
        ZoneId zona = config.zona();
        List<RutaResponse.Parada> paradas = new ArrayList<>();
        List<Punto> puntos = new ArrayList<>();
        List<String> advertencias = new ArrayList<>();
        int resueltas = 0;

        for (RutaParada parada : ruta.getParadas()) {
            Guia guia = parada.getGuia();
            Punto punto = new Punto(guia.getLatitud(), guia.getLongitud());
            LocalTime llegada = parada.getLlegadaEstimada().atZone(zona).toLocalTime();
            boolean fueraDeHorario = (guia.getVentanaDesde() != null && llegada.isBefore(guia.getVentanaDesde()))
                    || (guia.getVentanaHasta() != null && llegada.isAfter(guia.getVentanaHasta()));

            String identificacion = "Folio " + guia.getFolio() + " (" + guia.getCliente() + ")";
            if (fueraDeHorario) {
                advertencias.add(identificacion + ": llegada estimada " + llegada.format(HORA)
                        + ", recibe " + rango(guia.getVentanaDesde(), guia.getVentanaHasta()));
            }
            if (guia.isUbicacionAproximada()) {
                advertencias.add(identificacion + ": la dirección se ubicó de forma aproximada, confirmarla");
            }

            paradas.add(new RutaResponse.Parada(parada.getOrden(), guia.getId(), guia.getFolio(), guia.getCliente(),
                    guia.getDireccion(), guia.getLatitud(), guia.getLongitud(), guia.isUbicacionAproximada(),
                    llegada.withSecond(0).withNano(0), guia.getVentanaDesde(), guia.getVentanaHasta(),
                    guia.getOrigenHorario(), fueraDeHorario, guia.getNotaEntrega(), guia.getComentario(),
                    guia.getFooter(), LinksNavegacion.waze(punto), LinksNavegacion.googleMaps(punto),
                    parada.getResultado()));
            puntos.add(punto);
            if (parada.getResultado() != null) {
                resueltas++;
            }
        }

        Punto origen = new Punto(config.getOrigenLatitud(), config.getOrigenLongitud());
        // Con vuelta, el plan cuenta el regreso: el link termina en la bodega para que Google
        // Maps muestre la misma ruta, y los mismos km, que el plan. La bodega no es una parada.
        if (ruta.isVuelveABodega() && !puntos.isEmpty()) {
            puntos.add(origen);
        }
        Instant salida = ruta.getFecha().atTime(ruta.getHoraSalida()).atZone(zona).toInstant();
        Instant termino = ruta.getTerminoEstimado();
        return new RutaResponse(ruta.getId(), ruta.getRepartidorId(), ruta.getFecha(), ruta.getHoraSalida(),
                ruta.isVuelveABodega(),
                ruta.getKmPlanificados() == null ? null : Math.round(ruta.getKmPlanificados() * 10) / 10.0,
                termino == null ? null : (int) Duration.between(salida, termino).toMinutes(),
                hora(termino, zona),
                ruta.getOdometroSalida(), hora(ruta.getOdometroSalidaEn(), zona),
                ruta.getOdometroTermino(), hora(ruta.getOdometroTerminoEn(), zona),
                paradas, LinksNavegacion.tramosGoogleMaps(origen, puntos, config.getParadasPorTramoMaps()), advertencias,
                estado(ruta, paradas.size(), resueltas));
    }

    private EstadoRuta estado(Ruta ruta, int paradas, int resueltas) {
        return EstadoRuta.de(paradas, resueltas, ruta.isVuelveABodega(), ruta.getOdometroSalida() != null,
                ruta.getOdometroTermino() != null, ruta.getFecha().isBefore(LocalDate.now(config.zona())));
    }

    private static LocalTime hora(Instant instante, ZoneId zona) {
        return instante == null ? null : instante.atZone(zona).toLocalTime().withSecond(0).withNano(0);
    }

    private static String rango(LocalTime desde, LocalTime hasta) {
        if (desde != null && hasta != null) {
            return "de " + desde.format(HORA) + " a " + hasta.format(HORA);
        }
        return desde != null ? "desde las " + desde.format(HORA) : "hasta las " + hasta.format(HORA);
    }
}
