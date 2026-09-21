package com.calimport.guias.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import com.calimport.guias.controller.dto.RutaResponse;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.Repartidor;
import com.calimport.guias.model.Rol;
import com.calimport.guias.model.Ruta;
import com.calimport.guias.model.RutaParada;
import com.calimport.guias.repository.RutaRepository;
import com.calimport.guias.service.Geocodificador.Ubicacion;
import com.calimport.guias.service.InterpreteComentarios.Interpretacion;
import com.calimport.guias.service.LinksNavegacion.Punto;
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
        if (repartidor.getRol() != Rol.REPARTIDOR) {
            throw new ApiException(HttpStatus.CONFLICT, repartidor.getNombre() + " no es repartidor");
        }

        List<Guia> guias = interpretarComentarios(ubicar(cargarGuias(request.guias())));

        List<Visita> visitas;
        try {
            visitas = optimizadorRutas.optimizar(request.fecha(), request.horaSalida(), guias);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Error optimizando la ruta del repartidor {}: {}", request.repartidorId(), e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Error calculando el orden de la ruta");
        }

        return transacciones.execute(estado -> guardar(request, guias, visitas));
    }

    @Transactional(readOnly = true)
    public RutaResponse obtener(Integer repartidorId, LocalDate fecha) {
        Ruta ruta = rutaRepository.findByRepartidorIdAndFecha(repartidorId, fecha)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No hay ruta para el repartidor " + repartidorId + " el " + fecha));
        return aRespuesta(ruta);
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
                            + ". Corregirla en SAP y volver a sincronizar.");
        }
        return ubicadas;
    }

    /**
     * Si la interpretación falla, la ruta se genera igual sin ese horario: es preferible una ruta que
     * ignore un comentario a no tener ruta. El comentario viaja completo en la respuesta,
     * así que el repartidor lo ve de todas formas, y se reintenta en la próxima generación.
     */
    private List<Guia> interpretarComentarios(List<Guia> guias) {
        List<Guia> resultado = new ArrayList<>();
        for (Guia guia : guias) {
            if (guia.getOrigenHorario() != null || guia.getComentario() == null) {
                resultado.add(guia);
                continue;
            }
            try {
                Interpretacion i = interpreteComentarios.interpretar(guia.getComentario());
                resultado.add(guiaService.guardarHorarioInterpretado(guia.getId(), i.desde(), i.hasta(), i.nota()));
            } catch (RuntimeException e) {
                log.warn("No se pudo interpretar el comentario de la guia {}: {}", guia.getId(), e.getMessage());
                resultado.add(guia);
            }
        }
        return resultado;
    }

    private RutaResponse guardar(GenerarRutaRequest request, List<Guia> guias, List<Visita> visitas) {
        List<Long> ids = guias.stream().map(Guia::getId).toList();
        // Regenerar reemplaza la ruta del día, y una guía reasignada sale de la ruta donde estaba.
        rutaRepository.borrarParadasPrevias(ids, request.repartidorId(), request.fecha());
        rutaRepository.borrarRuta(request.repartidorId(), request.fecha());

        Ruta ruta = new Ruta(request.repartidorId(), request.fecha(), request.horaSalida());
        int orden = 1;
        for (Visita visita : visitas) {
            Guia guia = guiaService.asignarRepartidor(visita.guiaId(), request.repartidorId());
            ruta.agregarParada(guia, orden++, visita.llegada());
        }
        return aRespuesta(rutaRepository.save(ruta));
    }

    RutaResponse aRespuesta(Ruta ruta) {
        ZoneId zona = config.zona();
        List<RutaResponse.Parada> paradas = new ArrayList<>();
        List<Punto> puntos = new ArrayList<>();
        List<String> advertencias = new ArrayList<>();

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
                    LinksNavegacion.waze(punto), LinksNavegacion.googleMaps(punto)));
            puntos.add(punto);
        }

        Punto origen = new Punto(config.getOrigenLatitud(), config.getOrigenLongitud());
        return new RutaResponse(ruta.getId(), ruta.getRepartidorId(), ruta.getFecha(), ruta.getHoraSalida(), paradas,
                LinksNavegacion.tramosGoogleMaps(origen, puntos, config.getParadasPorTramoMaps()), advertencias);
    }

    private static String rango(LocalTime desde, LocalTime hasta) {
        if (desde != null && hasta != null) {
            return "de " + desde.format(HORA) + " a " + hasta.format(HORA);
        }
        return desde != null ? "desde las " + desde.format(HORA) : "hasta las " + hasta.format(HORA);
    }
}
