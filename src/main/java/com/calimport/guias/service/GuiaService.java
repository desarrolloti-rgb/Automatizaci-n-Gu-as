package com.calimport.guias.service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.OrigenHorario;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.security.UsuarioActual;
import com.calimport.guias.utils.ApiException;

@Service
public class GuiaService {

    private GuiaRepository guiaRepository;

    public GuiaService(GuiaRepository guiaRepository) {
        this.guiaRepository = guiaRepository;
    }

    /** Da de alta una guía a partir de los datos que vienen de SAP. */
    @Transactional
    public Guia crear(int docEntry, Long folio, String cliente, String direccion) {
        guiaRepository.findByDocEntry(docEntry).ifPresent(g -> {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe una guía para el docEntry " + docEntry);
        });
        return guiaRepository.save(new Guia(docEntry, folio, cliente, direccion));
    }

    /**
     * Da de alta la guía, o refresca sus datos de SAP si ya estaba importada. Es lo que
     * llama la sincronización: a diferencia de {@link #crear}, encontrar la guía no es un
     * error, es el caso normal en la segunda corrida en adelante. Así llegan también las
     * correcciones hechas en SAP antes del despacho (una dirección mal escrita, por ejemplo).
     *
     * <p>Una guía ya resuelta no se toca: su contenido documenta lo que efectivamente se
     * entregó o se rechazó, y reescribirlo después borraría esa evidencia. Los campos que
     * genera la app (repartidor, estado, foto) nunca se tocan acá — solo viajan los datos
     * que son de SAP.
     *
     * <p>Lo que se calculó a partir de un dato de SAP sí se descarta cuando ese dato cambia:
     * si se corrige la dirección, las coordenadas viejas apuntarían a otro lugar; si cambia
     * el comentario, el horario interpretado ya no corresponde. Un horario que definió el
     * bodeguero, en cambio, se respeta.
     */
    @Transactional
    public Guia sincronizarDesdeSap(int docEntry, Long folio, String cliente, String direccion, String comentario) {
        Guia guia = guiaRepository.findByDocEntry(docEntry)
                .orElseGet(() -> new Guia(docEntry, folio, cliente, direccion));

        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            return guia;
        }

        String comentarioNuevo = (comentario == null || comentario.isBlank()) ? null : comentario;

        if (!Objects.equals(guia.getDireccion(), direccion)) {
            guia.setLatitud(null);
            guia.setLongitud(null);
            guia.setUbicacionAproximada(false);
        }
        if (!Objects.equals(guia.getComentario(), comentarioNuevo)
                && guia.getOrigenHorario() == OrigenHorario.COMENTARIO) {
            limpiarHorario(guia);
        }

        guia.setFolio(folio);
        guia.setCliente(cliente);
        guia.setDireccion(direccion);
        guia.setComentario(comentarioNuevo);
        return guiaRepository.save(guia);
    }

    /**
     * El bodeguero fija el horario de recepción, o lo corrige si lo interpretado del
     * comentario está mal. Manda sobre el comentario: una sincronización posterior no lo pisa.
     *
     * <p>Mandar todo vacío devuelve el control al comentario de SAP, que se vuelve a
     * interpretar al generar la ruta.
     */
    @Transactional
    public Guia definirHorario(Long id, LocalTime desde, LocalTime hasta, String nota) {
        if (desde != null && hasta != null && !desde.isBefore(hasta)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La hora 'desde' debe ser anterior a 'hasta'");
        }
        Guia guia = obtenerPorId(id);
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo se puede cambiar el horario de una guía PENDIENTE");
        }

        String notaLimpia = (nota == null || nota.isBlank()) ? null : nota.trim();
        if (desde == null && hasta == null && notaLimpia == null) {
            limpiarHorario(guia);
        } else {
            guia.setVentanaDesde(desde);
            guia.setVentanaHasta(hasta);
            guia.setNotaEntrega(notaLimpia);
            guia.setOrigenHorario(OrigenHorario.BODEGA);
        }
        return guiaRepository.save(guia);
    }

    /**
     * Guarda lo interpretado del comentario. Si mientras tanto el bodeguero definió el
     * horario a mano, lo suyo gana y esto se descarta.
     */
    @Transactional
    public Guia guardarHorarioInterpretado(Long id, LocalTime desde, LocalTime hasta, String nota) {
        Guia guia = obtenerPorId(id);
        if (guia.getOrigenHorario() == OrigenHorario.BODEGA) {
            return guia;
        }
        guia.setVentanaDesde(desde);
        guia.setVentanaHasta(hasta);
        guia.setNotaEntrega(nota);
        guia.setOrigenHorario(OrigenHorario.COMENTARIO);
        return guiaRepository.save(guia);
    }

    @Transactional
    public Guia guardarUbicacion(Long id, double latitud, double longitud, boolean aproximada) {
        Guia guia = obtenerPorId(id);
        guia.setLatitud(latitud);
        guia.setLongitud(longitud);
        guia.setUbicacionAproximada(aproximada);
        return guiaRepository.save(guia);
    }

    private static void limpiarHorario(Guia guia) {
        guia.setVentanaDesde(null);
        guia.setVentanaHasta(null);
        guia.setNotaEntrega(null);
        guia.setOrigenHorario(null);
    }

    //Permite leer las guías sin necesidad de transacción, ya que no se modifican los datos.

    @Transactional(readOnly = true)
    public List<Guia> listar() {
        return guiaRepository.findAll();
    }


    //Lista las guías que están en un estado específico (PENDIENTE, ENTREGADA, RECHAZADA).
    @Transactional(readOnly = true)
    public List<Guia> listarPorEstado(EstadoGuia estado) {
        return guiaRepository.findByEstado(estado);
    }

    //Lista las guías asignadas a un repartidor específico, identificado por su employeeId.

    @Transactional(readOnly = true)
    public List<Guia> listarPorRepartidor(Integer repartidorId) {
        return guiaRepository.findByRepartidorId(repartidorId);
    }

    /**
     * Listado según quién pregunta. El jefe de bodega ve todas, con el filtro que pida (si
     * vienen los dos, manda el estado). Un repartidor ve solo las suyas: el estado filtra
     * dentro de ésas, y pedir las de otro repartidor es 403, no una lista vacía.
     */
    @Transactional(readOnly = true)
    public List<Guia> listarPara(UsuarioActual usuario, EstadoGuia estado, Integer repartidorId) {
        if (usuario.esJefeBodega()) {
            if (estado != null) {
                return listarPorEstado(estado);
            }
            return repartidorId != null ? listarPorRepartidor(repartidorId) : listar();
        }
        if (repartidorId != null && repartidorId != usuario.employeeId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo puede consultar sus propias guías");
        }
        return estado != null
                ? guiaRepository.findByRepartidorIdAndEstado(usuario.employeeId(), estado)
                : listarPorRepartidor(usuario.employeeId());
    }

    //Permite obtener una guía por su id, lanzando una excepción si no existe.

    @Transactional(readOnly = true)
    public Guia obtenerPorId(Long id) {
        return guiaRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No existe una guía con id " + id));
    }

    /** El jefe de bodega ve cualquier guía; un repartidor, solo las que tiene asignadas. */
    @Transactional(readOnly = true)
    public Guia obtenerPara(Long id, UsuarioActual usuario) {
        return usuario.esJefeBodega() ? obtenerPorId(id) : obtenerPropia(id, usuario.employeeId());
    }

    /**
     * La guía, siempre que esté asignada a ese repartidor. Si es de otro (o de nadie) es
     * 403 y no 404: la guía existe, lo que falta es permiso sobre ella.
     */
    @Transactional(readOnly = true)
    public Guia obtenerPropia(Long id, int repartidorId) {
        Guia guia = obtenerPorId(id);
        if (guia.getRepartidorId() == null || guia.getRepartidorId() != repartidorId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "La guía no está asignada a este repartidor");
        }
        return guia;
    }

    // Bodega asigna qué repartidor lleva la guía. Solo antes de que empiece el reparto. */
    @Transactional
    public Guia asignarRepartidor(Long id, Integer repartidorId) {
        if (repartidorId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "repartidorId es obligatorio");
        }
        Guia guia = obtenerPorId(id);
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo se puede asignar repartidor a una guía PENDIENTE");
        }
        guia.setRepartidorId(repartidorId);
        return guiaRepository.save(guia);
    }

    // El repartidor confirma que retiró la guía para salir a reparto. */
    @Transactional
    public Guia marcarRecibidaPorRepartidor(Long id, int repartidorId) {
        Guia guia = obtenerPropia(id, repartidorId);
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo se puede recibir una guía PENDIENTE");
        }
        guia.setRecibidaPorRepartidor(true);
        guia.setFechaRecepcionRepartidor(Instant.now());
        return guiaRepository.save(guia);
    }

    // Cliente recibió todo y firmó: se guarda la evidencia fotográfica. */
    @Transactional
    public Guia entregar(Long id, int repartidorId, String urlFoto, String hashFoto) {
        if (urlFoto == null || urlFoto.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "urlFoto es obligatoria para entregar la guía");
        }
        if (hashFoto == null || hashFoto.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "hashFoto es obligatorio para entregar la guía");
        }
        Guia guia = obtenerPropia(id, repartidorId);
        validarQuePuedaResolverse(guia);
        guia.setEstado(EstadoGuia.ENTREGADA);
        guia.setFechaEntrega(Instant.now());
        guia.setUrlFoto(urlFoto);
        guia.setHashFoto(hashFoto);
        return guiaRepository.save(guia);
    }

    // Cliente rechazó el despacho completo. 
    @Transactional
    public Guia rechazar(Long id, int repartidorId) {
        Guia guia = obtenerPropia(id, repartidorId);
        validarQuePuedaResolverse(guia);
        guia.setEstado(EstadoGuia.RECHAZADA);
        guia.setFechaEntrega(Instant.now());
        return guiaRepository.save(guia);
    }

    // Marca que el resultado (entrega o rechazo) ya viajó de vuelta a SAP. */
    @Transactional
    public Guia marcarSincronizada(Long id, int repartidorId) {
        Guia guia = obtenerPropia(id, repartidorId);
        guia.setSincronizada(true);
        return guiaRepository.save(guia);
    }

    private void validarQuePuedaResolverse(Guia guia) {
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "La guía ya fue resuelta (estado " + guia.getEstado() + ")");
        }
    }
}
