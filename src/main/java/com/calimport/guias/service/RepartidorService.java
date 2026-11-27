package com.calimport.guias.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

import com.calimport.guias.model.Repartidor;
import com.calimport.guias.model.Rol;
import com.calimport.guias.repository.RepartidorRepository;
import com.calimport.guias.sap.EmpleadoSap;
import com.calimport.guias.utils.ApiException;

@Service
public class RepartidorService {

    private static final Logger log = LoggerFactory.getLogger(RepartidorService.class);

    private final RepartidorRepository repartidorRepository;

    public RepartidorService(RepartidorRepository repartidorRepository) {
        this.repartidorRepository = repartidorRepository;
    }

    @Transactional(readOnly = true)
    public List<Repartidor> listar() {
        return repartidorRepository.findAll();
    }

    /** Los que pueden recibir guías: activos y con rol Despachador (el jefe de bodega no reparte). */
    @Transactional(readOnly = true)
    public List<Repartidor> listarActivos() {
        return repartidorRepository.findByActivoTrueAndRol(Rol.Despachador);
    }

    @Transactional(readOnly = true)
    public Repartidor obtenerPorId(int employeeId) {
        return repartidorRepository.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No existe un repartidor con id " + employeeId));
    }

    /**
     * Guarda o actualiza la copia local con lo último que vino de SAP. Se llama en cada
     * login exitoso: es la forma en que la copia local se mantiene al día, sin un job aparte.
     */
    @Transactional
    public Repartidor upsertDesdeSap(int employeeId, String nombre, String email, boolean activo, Rol rol) {
        Repartidor repartidor = repartidorRepository.findById(employeeId).orElseGet(Repartidor::new);
        repartidor.setEmployeeId(employeeId);
        repartidor.setNombre(nombre);
        repartidor.setEmail(email);
        repartidor.setActivo(activo);
        repartidor.setRol(rol);
        return repartidorRepository.save(repartidor);
    }

    /**
     * Lo que cambió en el selector de repartidores de bodega al sincronizar con SAP.
     *
     * @param altas despachadores que ahora se pueden elegir y antes no (nuevos o reactivados).
     * @param bajas los que se podían elegir y ya no: inactivos en SAP, con otro cargo o que ya no
     *     están en SAP.
     * @param omitidos despachadores activos en SAP que no se pudieron cargar: sin correo, o con
     *     el correo de otro empleado (es único en la tabla, y con él se inicia sesión).
     */
    public record CambiosRepartidores(List<String> altas, List<String> bajas, List<String> omitidos) {
    }

    /**
     * Pone la copia local al día con todos los empleados de SAP, sin esperar a que cada uno
     * inicie sesión: así un despachador nuevo se puede elegir desde su primer día y el que se
     * va deja de aparecer.
     *
     * <ul>
     *   <li>Un empleado activo con cargo de bodega queda activo, con el nombre, correo y rol de
     *       SAP. Lo que no viene de SAP ({@code vuelveABodega}) no se toca.</li>
     *   <li>Cualquier otro que estuviera activo queda inactivo: inactivo en SAP, con un cargo
     *       que ya no entra a Guías, o que ya no está en SAP. No se borra, porque sus guías lo
     *       siguen nombrando.</li>
     *   <li>Una lista vacía no da de baja a nadie: es más probable un problema al leer SAP que
     *       una empresa sin empleados.</li>
     * </ul>
     */
    @Transactional
    public CambiosRepartidores sincronizarConSap(List<EmpleadoSap> empleados) {
        if (empleados.isEmpty()) {
            return new CambiosRepartidores(List.of(), List.of(), List.of());
        }
        Map<Integer, Repartidor> locales = new LinkedHashMap<>();
        for (Repartidor r : repartidorRepository.findAll()) {
            locales.put(r.getEmployeeId(), r);
        }
        Set<Integer> antes = despachadoresActivos(locales.values());
        // De quién es cada correo: un correo que ya tiene otro empleado no se puede guardar.
        Map<String, Integer> duenoDelCorreo = new HashMap<>();
        locales.values().forEach(r -> duenoDelCorreo.put(clave(r.getEmail()), r.getEmployeeId()));

        Set<Integer> vigentes = new HashSet<>();
        List<String> omitidos = new ArrayList<>();
        for (EmpleadoSap empleado : empleados) {
            Optional<Rol> rol = empleado.rol();
            if (!empleado.activo() || rol.isEmpty()) {
                continue;
            }
            Integer dueno = duenoDelCorreo.get(clave(empleado.email()));
            if (empleado.email().isBlank() || (dueno != null && dueno != empleado.employeeId())) {
                log.warn("Empleado {} ({}) no se carga como repartidor: {}", empleado.employeeId(), empleado.nombre(),
                        empleado.email().isBlank() ? "no tiene correo en SAP" : "su correo ya es de otro empleado");
                if (rol.get() == Rol.Despachador) {
                    omitidos.add(empleado.nombre());
                }
                continue;
            }
            vigentes.add(empleado.employeeId());
            Repartidor actual = locales.get(empleado.employeeId());
            if (actual != null && actual.isActivo() && actual.getRol() == rol.get()
                    && actual.getNombre().equals(empleado.nombre()) && actual.getEmail().equals(empleado.email())) {
                continue;
            }
            Repartidor repartidor = actual != null ? actual : new Repartidor();
            if (actual != null) {
                duenoDelCorreo.remove(clave(actual.getEmail()));
            }
            repartidor.setEmployeeId(empleado.employeeId());
            repartidor.setNombre(empleado.nombre());
            repartidor.setEmail(empleado.email());
            repartidor.setActivo(true);
            repartidor.setRol(rol.get());
            locales.put(empleado.employeeId(), repartidorRepository.save(repartidor));
            duenoDelCorreo.put(clave(empleado.email()), empleado.employeeId());
        }

        for (Repartidor repartidor : locales.values()) {
            if (repartidor.isActivo() && !vigentes.contains(repartidor.getEmployeeId())) {
                repartidor.setActivo(false);
                repartidorRepository.save(repartidor);
            }
        }

        Set<Integer> despues = despachadoresActivos(locales.values());
        CambiosRepartidores cambios = new CambiosRepartidores(
                nombres(locales, despues, antes), nombres(locales, antes, despues), List.copyOf(omitidos));
        if (!cambios.altas().isEmpty() || !cambios.bajas().isEmpty() || !omitidos.isEmpty()) {
            log.info("Repartidores al dia con SAP: altas {}, bajas {}, omitidos {}",
                    cambios.altas(), cambios.bajas(), omitidos);
        }
        return cambios;
    }

    private static Set<Integer> despachadoresActivos(Collection<Repartidor> repartidores) {
        Set<Integer> ids = new HashSet<>();
        for (Repartidor r : repartidores) {
            if (r.isActivo() && r.getRol() == Rol.Despachador) {
                ids.add(r.getEmployeeId());
            }
        }
        return ids;
    }

    /** Los nombres de los que están en {@code en} y no en {@code sin}, en el orden de la tabla. */
    private static List<String> nombres(Map<Integer, Repartidor> locales, Set<Integer> en, Set<Integer> sin) {
        return locales.values().stream()
                .filter(r -> en.contains(r.getEmployeeId()) && !sin.contains(r.getEmployeeId()))
                .map(Repartidor::getNombre)
                .toList();
    }

    private static String clave(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Deja como habitual lo que bodega marcó al generar su ruta. Uno de los repartidores vuelve
     * a bodega al terminar y el otro no: así el formulario lo trae marcado la próxima vez.
     */
    @Transactional
    public void recordarVuelta(int employeeId, boolean vuelveABodega) {
        Repartidor repartidor = obtenerPorId(employeeId);
        if (repartidor.isVuelveABodega() != vuelveABodega) {
            repartidor.setVuelveABodega(vuelveABodega);
            repartidorRepository.save(repartidor);
        }
    }
}
