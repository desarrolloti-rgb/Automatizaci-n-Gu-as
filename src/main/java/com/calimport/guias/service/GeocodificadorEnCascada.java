package com.calimport.guias.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.calimport.guias.config.GoogleConfig;
import com.calimport.guias.config.RutasConfig;
import com.calimport.guias.google.GeocodingClient;
import com.calimport.guias.osm.NominatimClient;

/**
 * Intenta primero con OpenStreetMap y solo paga Google cuando OSM <b>no encuentra nada</b>.
 * Se activa con {@code rutas.geocodificador=cascada}.
 *
 * <p>Existe porque medimos el problema con direcciones reales de esta instalación. De cinco
 * que Nominatim no encontró:
 *
 * <ul>
 *   <li>Dos eran <b>errores de tipeo en SAP</b> ("VICUÑA MACKENA", "DOMINGO ARTEGA").
 *       Escritas bien, Nominatim las ubica exacto; con el typo no las ubica nunca, porque
 *       busca por coincidencia exacta. Google sí las tolera.
 *   <li>Tres eran direcciones que <b>existen</b> pero escritas como las escribe un vendedor
 *       ("Bodega Planta, SITE CPP (Promedio) KM. 63 LONGITUDINAL SUR", "CAMINO LONGITUDINAL
 *       SUR # 5201"): Nominatim las encuentra solo si se les limpia el texto a mano.
 * </ul>
 *
 * <p>O sea que el límite no es de configuración: Nominatim busca literal y las direcciones
 * de SAP no vienen literales. Limpiar el texto con reglas tapa algunos casos y ninguno de
 * los typos.
 *
 * <p><b>Google solo entra cuando OSM devuelve vacío</b>, no cuando devuelve una ubicación
 * aproximada. Una aproximada ya sirve —la guía entra a la ruta y queda marcada para que el
 * repartidor confirme— y la mayoría de las direcciones caen ahí: cobrarlas todas sería
 * pagar por lo que ya estaba resuelto. Lo que se paga es solo lo que hoy bloquea la ruta.
 *
 * <p>Si Google falla (falta la API key, se acabó la cuota), <b>no se propaga</b>: se
 * registra y se devuelve lo que dijo OSM. Perder el respaldo pagado no puede dejar sin ruta
 * a las guías que el geocodificador gratuito sí resolvió.
 */
@Component
@ConditionalOnProperty(prefix = "rutas", name = "geocodificador", havingValue = "cascada")
public class GeocodificadorEnCascada implements Geocodificador {

    private static final Logger log = LoggerFactory.getLogger(GeocodificadorEnCascada.class);

    private final Geocodificador gratuito;
    private final Geocodificador pagado;

    /**
     * Construye sus dos geocodificadores en vez de recibirlos inyectados: cada uno declara
     * su propio {@code @ConditionalOnProperty} sobre {@code rutas.geocodificador}, así que
     * con el valor en "cascada" ninguno de los dos llega a ser bean. Los constructores no
     * hacen nada caro — arman un RestClient — y así la selección sigue viviendo en una sola
     * propiedad en vez de repartirse en condiciones cruzadas.
     */
    @Autowired
    public GeocodificadorEnCascada(RutasConfig rutasConfig, GoogleConfig googleConfig) {
        this(new NominatimClient(rutasConfig), new GeocodingClient(googleConfig));
    }

    GeocodificadorEnCascada(Geocodificador gratuito, Geocodificador pagado) {
        this.gratuito = gratuito;
        this.pagado = pagado;
    }

    @Override
    public Optional<Ubicacion> geocodificar(String direccion) {
        Optional<Ubicacion> conOsm = gratuito.geocodificar(direccion);
        if (conOsm.isPresent()) {
            return conOsm;
        }
        try {
            Optional<Ubicacion> conGoogle = pagado.geocodificar(direccion);
            if (conGoogle.isEmpty()) {
                log.info("Ni OpenStreetMap ni Google ubicaron la direccion: {}", direccion);
            }
            return conGoogle;
        } catch (RuntimeException e) {
            log.warn("Google no pudo geocodificar '{}' ({}). Queda sin ubicar, como con OSM solo.",
                    direccion, e.getMessage());
            return Optional.empty();
        }
    }
}
