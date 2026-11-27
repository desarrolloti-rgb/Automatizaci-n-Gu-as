package com.calimport.guias.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.config.RutasConfig;
import com.calimport.guias.correo.CorreoDespachoService;
import com.calimport.guias.model.EstadoGuia;
import com.calimport.guias.model.Guia;
import com.calimport.guias.model.OrigenDireccion;
import com.calimport.guias.model.OrigenHorario;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.repository.RutaRepository;
import com.calimport.guias.sap.FooterDespacho;
import com.calimport.guias.sap.GuiaSap;
import com.calimport.guias.security.UsuarioActual;
import com.calimport.guias.utils.ApiException;

@Service
public class GuiaService {

    private GuiaRepository guiaRepository;
    private final SincronizacionSapService sincronizacionSap;
    private final RutaRepository rutaRepository;
    private final CorreoDespachoService correoDespacho;
    private final List<String> transportistas;
    private final RutasConfig rutas;

    /**
     * @param transportistas los transportistas externos que bodega puede elegir
     *     ({@code guias.transportistas}, "SAMEX"). Vacío = no se puede marcar ninguna guía.
     * @param rutas de ahí sale qué día es hoy: sacar una guía de las rutas no toca las de días
     *     anteriores, que son el historial
     */
    public GuiaService(GuiaRepository guiaRepository, SincronizacionSapService sincronizacionSap,
                       RutaRepository rutaRepository, CorreoDespachoService correoDespacho,
                       @Value("${guias.transportistas:SAMEX}") List<String> transportistas,
                       RutasConfig rutas) {
        this.guiaRepository = guiaRepository;
        this.sincronizacionSap = sincronizacionSap;
        this.rutaRepository = rutaRepository;
        this.correoDespacho = correoDespacho;
        this.transportistas = transportistas.stream().map(String::trim).filter(t -> !t.isEmpty()).toList();
        this.rutas = rutas;
    }

    /** Hoy en la zona de las rutas: la VM corre en UTC y de noche ya sería mañana. */
    private LocalDate hoy() {
        return LocalDate.now(rutas.zona());
    }

    /**
     * Guarda el cambio y lo refleja en SAP, en ese orden.
     *
     * <p>Primero Postgres porque es lo que no puede perderse: el repartidor ya entregó y
     * esa evidencia tiene que quedar aunque el Service Layer esté caído. El envío a SAP no
     * lanza si falla — deja la guía marcada para reintento (ver
     * {@link SincronizacionSapService}).
     */
    private Guia guardarYReflejar(Guia guia) {
        guia.setSincronizada(false);
        Guia guardada = guiaRepository.save(guia);
        sincronizacionSap.empujar(guardada);
        return guardada;
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
     * el comentario, el horario interpretado ya no corresponde. Un horario o una dirección
     * que definió el bodeguero, en cambio, se respetan: son los únicos datos que alguien
     * miró y confirmó, y volver a pisarlos con lo que dice SAP desharía esa corrección en
     * la siguiente sincronización.
     *
     * <p>El pie del documento se refresca <b>siempre</b>, incluso cuando bodega corrigió la
     * dirección: es lo que el documento dice hoy, y es justamente contra lo que bodega
     * compara. Lo que no se toca es la decisión que tomó a partir de él.
     */
    @Transactional
    public Guia sincronizarDesdeSap(GuiaSap datos) {
        Guia guia = guiaRepository.findByDocEntry(datos.docEntry())
                .orElseGet(() -> new Guia(datos.docEntry(), datos.folio(), datos.cliente(), datos.direccion()));

        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            // La fecha del documento no cambia ni es evidencia de nada: se completa también
            // en una resuelta, para que las importadas antes de existir el campo la reciban.
            if (guia.getFechaDocumento() == null && datos.fechaDocumento() != null) {
                guia.setFechaDocumento(datos.fechaDocumento());
                return guiaRepository.save(guia);
            }
            return guia;
        }

        String comentarioNuevo = vacioANull(datos.comentario());
        String horarioFooterNuevo = vacioANull(datos.horarioFooter());
        boolean direccionDeBodega = guia.getOrigenDireccion() == OrigenDireccion.BODEGA;

        if (!direccionDeBodega && !Objects.equals(guia.getDireccion(), datos.direccion())) {
            guia.setLatitud(null);
            guia.setLongitud(null);
            guia.setUbicacionAproximada(false);
        }
        // El horario interpretado se descarta cuando cambia el texto del que salió, sea el
        // pie o el comentario: si el documento dice otra cosa, la ventana vieja ya no vale.
        if (guia.getOrigenHorario() == OrigenHorario.COMENTARIO
                && (!Objects.equals(guia.getComentario(), comentarioNuevo)
                    || !Objects.equals(guia.getHorarioFooter(), horarioFooterNuevo))) {
            limpiarHorario(guia);
        }

        guia.setFolio(datos.folio());
        guia.setCliente(datos.cliente());
        if (!direccionDeBodega) {
            guia.setDireccion(datos.direccion());
            guia.setOrigenDireccion(datos.origenDireccion());
        }
        guia.setComentario(comentarioNuevo);
        guia.setFooter(vacioANull(datos.footer()));
        guia.setDireccionFooter(vacioANull(datos.direccionFooter()));
        guia.setHorarioFooter(horarioFooterNuevo);
        // Lo que el pie dice del transportista, con el mismo parser de la sincronización. Es una
        // pista para bodega ("Pie: SAMEX"); quien la marca es bodega, en transportista.
        guia.setTransportistaFooter(recortar(vacioANull(FooterDespacho.de(datos.footer()).transportista()), 50));
        if (datos.fechaDocumento() != null) {
            guia.setFechaDocumento(datos.fechaDocumento());
        }
        // Al crear la guía en SAP se puede dejar U_Despachador = SAMEX: llega ya marcada, sin que
        // bodega tenga que hacerlo en la app. Solo si nadie la tomó todavía: ni un transportista (ya
        // está marcada, y no se avisa a SAP de nuevo en cada sincronización) ni un repartidor (lo que
        // bodega decidió en la app no lo pisa lo que alguien escribió en SAP al crearla).
        String transportistaDeSap = transportistaDeSap(datos.despachador());
        if (transportistaDeSap != null && guia.getTransportista() == null
                && guia.getRepartidorId() == null && !guia.isRecibidaPorRepartidor()) {
            guia.setTransportista(transportistaDeSap);
            guia.setRecibidaPorRepartidor(true);
            guia.setFechaRecepcionRepartidor(Instant.now());
            // En SAP está en P con SAMEX: pasa a T, como si bodega la hubiera marcado en la app.
            return guardarYReflejar(guia);
        }
        return guiaRepository.save(guia);
    }

    /**
     * El jefe de bodega corrige la dirección de despacho, leyendo el pie del documento.
     *
     * <p>Manda sobre las dos fuentes de SAP y ninguna sincronización la vuelve a pisar
     * (queda en {@link OrigenDireccion#BODEGA}). Es la contrapartida de no confiar en la
     * dirección de la ficha del cliente: si no se pudiera corregir, una dirección vieja no
     * tendría arreglo salvo editar SAP.
     *
     * <p>No admite dejarla vacía, a diferencia del horario: una guía sin horario se ordena
     * por cercanía y se entrega igual, pero una sin dirección no se puede ubicar ni rutear.
     * El pie sigue guardado, así que para volver atrás se copia de ahí.
     */
    @Transactional
    public Guia definirDireccion(Long id, String direccion) {
        String limpia = direccion == null ? "" : direccion.trim();
        if (limpia.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La dirección no puede quedar vacía");
        }
        // El @Size del DTO ya corta en el borde; esto cubre los caminos que no pasan por el
        // controller. Sin el tope, pasarse del largo de la columna sale como 500.
        if (limpia.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La dirección no puede superar los 255 caracteres");
        }
        Guia guia = obtenerPorId(id);
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo se puede cambiar la dirección de una guía PENDIENTE");
        }
        if (!Objects.equals(guia.getDireccion(), limpia)) {
            // Las coordenadas eran de la dirección anterior: apuntarían a otro lugar.
            guia.setLatitud(null);
            guia.setLongitud(null);
            guia.setUbicacionAproximada(false);
        }
        guia.setDireccion(limpia);
        guia.setOrigenDireccion(OrigenDireccion.BODEGA);
        return guiaRepository.save(guia);
    }

    private static String vacioANull(String texto) {
        return (texto == null || texto.isBlank()) ? null : texto;
    }

    private static String recortar(String texto, int largo) {
        return texto == null || texto.length() <= largo ? texto : texto.substring(0, largo);
    }

    /** Los transportistas externos que bodega puede elegir, para el selector de la tabla. */
    public List<String> transportistas() {
        return transportistas;
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

    /**
     * Asigna el repartidor al generar una ruta ({@link RutaService}). La ruta ya se encarga
     * de sacar la guía de la ruta anterior; bodega, desde la tabla, usa {@link #reasignar}.
     */
    @Transactional
    public Guia asignarRepartidor(Long id, Integer repartidorId) {
        if (repartidorId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "repartidorId es obligatorio");
        }
        Guia guia = obtenerPendiente(id);
        // RutaService ya las descarta al cargar; esto cubre que bodega la haya marcado mientras
        // se generaba la ruta. Que la lleve un transportista lo decidió bodega, y armar una
        // ruta no puede deshacerlo por el costado.
        if (guia.getTransportista() != null) {
            throw new ApiException(HttpStatus.CONFLICT, noEntraEnRuta(guia));
        }
        return cambiarRepartidor(guia, repartidorId);
    }

    /** El motivo por el que una guía que lleva un transportista externo no puede ir en una ruta. */
    public static String noEntraEnRuta(Guia guia) {
        return "La guía folio " + guia.getFolio() + " va por " + guia.getTransportista() + ": no entra en una ruta";
    }

    /**
     * Bodega cambia o quita el repartidor de una guía pendiente. {@code null} la deja sin
     * asignar, para dársela a otro más tarde.
     *
     * <p>Además la saca de la ruta donde estaba: si quedara ahí, el repartidor anterior la
     * seguiría viendo en su recorrido sin poder tocarla.
     */
    @Transactional
    public Guia reasignar(Long id, Integer repartidorId) {
        Guia guia = obtenerPendiente(id);
        if (Objects.equals(guia.getRepartidorId(), repartidorId) && guia.getTransportista() == null) {
            return guia;
        }
        // Antes de tocar la guía: el delete limpia el contexto de persistencia, y lo que se
        // cambie después se guarda igual (save hace merge).
        rutaRepository.quitarGuiaDeRutas(id, hoy());
        if (guia.getTransportista() != null) {
            // Deja de ir por el transportista externo: es el mismo selector, elegir un
            // repartidor (o ninguno) le quita la marca. La había recibido el transportista, no
            // este repartidor, así que vuelve a "por retirar": en SAP, de T con el nombre del
            // transportista a P.
            guia.setTransportista(null);
            guia.setRepartidorId(repartidorId);
            guia.setRecibidaPorRepartidor(false);
            guia.setFechaRecepcionRepartidor(null);
            return guardarYReflejar(guia);
        }
        return cambiarRepartidor(guia, repartidorId);
    }

    /**
     * Bodega elige que la guía la lleve un transportista externo ("Despacho externo: SAMEX"), o
     * se lo quita con {@code null}. Es la otra opción del selector del repartidor, así que
     * marcarla le quita el repartidor y la saca de su ruta; asignarle un repartidor
     * ({@link #reasignar}) le quita el transportista.
     *
     * <p>Marcarla es entregársela al transportista, así que queda <b>recibida en ese momento</b>:
     * no hay un repartidor que la retire en la app. En SAP eso es T con el transportista en
     * {@code U_Despachador} ({@code EstadoLogistico}). Desmarcarla la devuelve a bodega, por
     * retirar: P.
     */
    @Transactional
    public Guia definirTransportista(Long id, String transportista) {
        String elegido = transportistaPermitido(transportista);
        Guia guia = obtenerPendiente(id, "Solo se puede cambiar quién lleva una guía PENDIENTE");
        if (Objects.equals(guia.getTransportista(), elegido)) {
            return guia;
        }
        rutaRepository.quitarGuiaDeRutas(id, hoy());
        guia.setTransportista(elegido);
        guia.setRepartidorId(null);
        guia.setRecibidaPorRepartidor(elegido != null);
        guia.setFechaRecepcionRepartidor(elegido != null ? Instant.now() : null);
        return guardarYReflejar(guia);
    }

    /**
     * El transportista de la lista que nombra el U_Despachador de SAP, con el nombre de la lista
     * ("samex " → "SAMEX"). Null si viene vacío o es otra cosa, como el nombre de una persona.
     */
    private String transportistaDeSap(String despachador) {
        if (despachador == null || despachador.isBlank()) {
            return null;
        }
        return transportistas.stream().filter(t -> t.equalsIgnoreCase(despachador.strip())).findFirst().orElse(null);
    }

    /** El nombre tal como está en la lista ("samex " → "SAMEX"); null si no viene ninguno. */
    private String transportistaPermitido(String pedido) {
        if (pedido == null || pedido.isBlank()) {
            return null;
        }
        String limpio = pedido.trim();
        return transportistas.stream()
                .filter(t -> t.equalsIgnoreCase(limpio))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, transportistas.isEmpty()
                        ? "No hay transportistas externos configurados (GUIAS_TRANSPORTISTAS)"
                        : "Transportista desconocido: " + limpio + ". Se puede elegir: "
                                + String.join(", ", transportistas)));
    }

    private Guia obtenerPendiente(Long id) {
        return obtenerPendiente(id, "Solo se puede asignar repartidor a una guía PENDIENTE");
    }

    private Guia obtenerPendiente(Long id, String siNoLoEsta) {
        Guia guia = obtenerPorId(id);
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, siNoLoEsta);
        }
        return guia;
    }

    /**
     * Si ya la había retirado otro, vuelve a "por retirar": quien la lleva ahora tiene que
     * confirmar que la tiene. En SAP pasa de T a P y se limpia {@code U_Despachador}, que si
     * no seguiría nombrando al anterior. Si no estaba retirada, SAP no cambia (P no lleva
     * despachador) y no hay nada que mandarle.
     */
    private Guia cambiarRepartidor(Guia guia, Integer repartidorId) {
        if (Objects.equals(guia.getRepartidorId(), repartidorId)) {
            return guia;
        }
        guia.setRepartidorId(repartidorId);
        if (!guia.isRecibidaPorRepartidor()) {
            return guiaRepository.save(guia);
        }
        guia.setRecibidaPorRepartidor(false);
        guia.setFechaRecepcionRepartidor(null);
        return guardarYReflejar(guia);
    }

    /**
     * El repartidor confirma que retiró la guía para salir a reparto. Es el paso que en
     * SAP mueve {@code U_EstadoLog} de P a T y deja su nombre en {@code U_Despachador}.
     */
    @Transactional
    public Guia marcarRecibidaPorRepartidor(Long id, int repartidorId) {
        Guia guia = obtenerPropia(id, repartidorId);
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo se puede recibir una guía PENDIENTE");
        }
        guia.setRecibidaPorRepartidor(true);
        guia.setFechaRecepcionRepartidor(Instant.now());
        return guardarYReflejar(guia);
    }

    /**
     * Cliente recibió todo y firmó: se guarda la evidencia fotográfica y queda programado el
     * correo que se lo avisa al cliente. El correo sale después y aparte
     * ({@link CorreoDespachoService}): el repartidor no espera ni a SAP ni al servidor de correo.
     */
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
        correoDespacho.programar(guia);
        return guardarYReflejar(guia);
    }

    /**
     * El transportista externo entregó la guía, según su portal: la cierra el lector de ese portal
     * ({@code EnviosExternosService}), no un repartidor.
     *
     * <p>Queda como una entrega propia, con dos diferencias. La hora es la que dice el
     * transportista, no la de cuando se enteró la app. Y el cliente no recibe el correo de despacho
     * (decisión del usuario para Samex): {@code correo_estado} queda en null, que es "no
     * corresponde". A SAP va como E con el transportista y el link al PDF.
     *
     * @param urlEvidencia el PDF ya guardado ({@code /api/fotos/...pdf}), con su hash
     */
    @Transactional
    public Guia entregarPorTransportista(Long id, String transportista, String urlEvidencia,
                                         String hashEvidencia, Instant fechaEntrega) {
        Guia guia = obtenerPorId(id);
        validarQuePuedaResolverse(guia);
        if (!Objects.equals(transportista, guia.getTransportista())) {
            // Bodega pudo pasársela a un repartidor entre la búsqueda y la llegada del PDF.
            throw new ApiException(HttpStatus.CONFLICT,
                    "La guía folio " + guia.getFolio() + " ya no va por " + transportista);
        }
        guia.setEstado(EstadoGuia.ENTREGADA);
        guia.setFechaEntrega(fechaEntrega);
        guia.setUrlFoto(urlEvidencia);
        guia.setHashFoto(hashEvidencia);
        return guardarYReflejar(guia);
    }

    /**
     * Cliente rechazó el despacho completo. El motivo es obligatorio y queda guardado: es la
     * evidencia del rechazo, lo que la foto es de la entrega.
     */
    @Transactional
    public Guia rechazar(Long id, int repartidorId, String motivo) {
        String motivoLimpio = motivo == null ? "" : motivo.trim();
        if (motivoLimpio.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Hay que indicar por qué el cliente rechazó la guía");
        }
        Guia guia = obtenerPropia(id, repartidorId);
        validarQuePuedaResolverse(guia);
        guia.setEstado(EstadoGuia.RECHAZADA);
        guia.setFechaEntrega(Instant.now());
        guia.setMotivoRechazo(motivoLimpio);
        return guardarYReflejar(guia);
    }

    /**
     * El jefe de bodega devuelve a pendiente una guía rechazada, para reintentar el
     * despacho otro día. En SAP es el flujo R → P, que limpia despachador y motivo.
     *
     * <p>Es la <b>única</b> excepción a que una guía resuelta no se toca, y es deliberada:
     * no se está reescribiendo lo que pasó, se está decidiendo volver a intentarlo. Por eso
     * queda en manos del jefe de bodega y no del repartidor, y por eso una ENTREGADA no se
     * puede reabrir: ahí el cliente ya firmó y existe una foto que lo prueba.
     *
     * <p>Se pierde el motivo del rechazo anterior, porque el flujo de SAP lo deja en null y
     * las dos copias tienen que decir lo mismo. Si mañana hace falta ese historial, es una
     * tabla aparte: no se puede reconstruir de acá.
     */
    @Transactional
    public Guia reabrir(Long id) {
        Guia guia = obtenerPorId(id);
        if (guia.getEstado() != EstadoGuia.RECHAZADA) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Solo se puede reabrir una guía RECHAZADA (esta está " + guia.getEstado() + ")");
        }
        // Queda sin dueño: tampoco puede seguir en la ruta de quien la rechazó.
        rutaRepository.quitarGuiaDeRutas(id, hoy());
        guia.setEstado(EstadoGuia.PENDIENTE);
        guia.setMotivoRechazo(null);
        guia.setFechaEntrega(null);
        // Vuelve a estar por retirar y sin dueño: bodega puede reasignarla a otro.
        guia.setRepartidorId(null);
        guia.setRecibidaPorRepartidor(false);
        guia.setFechaRecepcionRepartidor(null);
        return guardarYReflejar(guia);
    }

    /**
     * Fuerza el envío a SAP de una guía que quedó sin sincronizar, sin esperar al reintento
     * automático. Es una herramienta de operación para el jefe de bodega cuando SAP volvió
     * y no quiere esperar los minutos del ciclo.
     *
     * <p>Antes este método solo levantaba el flag a mano, cuando el envío no existía. Ahora
     * que el envío es real, marcarla sincronizada sin haberla enviado sería mentir: la
     * guía se daría por reflejada en SAP sin estarlo, y nadie volvería a intentarlo.
     */
    @Transactional
    public Guia reintentarSincronizacion(Long id) {
        Guia guia = obtenerPorId(id);
        sincronizacionSap.empujar(guia);
        return guia;
    }

    private void validarQuePuedaResolverse(Guia guia) {
        if (guia.getEstado() != EstadoGuia.PENDIENTE) {
            throw new ApiException(HttpStatus.CONFLICT, "La guía ya fue resuelta (estado " + guia.getEstado() + ")");
        }
    }
}
