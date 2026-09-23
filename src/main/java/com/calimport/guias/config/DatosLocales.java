package com.calimport.guias.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.Guia;
import com.calimport.guias.model.OrigenDireccion;
import com.calimport.guias.repository.GuiaRepository;
import com.calimport.guias.sap.FooterDespacho;
import com.calimport.guias.service.RepartidorService;

/**
 * Datos de ejemplo para el perfil local: sin SAP no hay de dónde importar guías.
 *
 * <p>Es código y no una migración de Flyway a propósito: una migración quedaría registrada
 * en la base y chocaría con las migraciones reales que vengan después.
 */
@Component
@ConditionalOnProperty(name = "local.datos-de-prueba", havingValue = "true")
public class DatosLocales implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatosLocales.class);

    private final GuiaRepository guiaRepository;
    private final RepartidorService repartidorService;
    private final LocalAuthConfig authConfig;

    public DatosLocales(GuiaRepository guiaRepository, RepartidorService repartidorService,
                        LocalAuthConfig authConfig) {
        this.guiaRepository = guiaRepository;
        this.repartidorService = repartidorService;
        this.authConfig = authConfig;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Los repartidores existen desde el arranque, para poder asignarles guías antes de que inicien sesión.
        authConfig.getUsuarios().forEach(u ->
                repartidorService.upsertDesdeSap(u.getEmployeeId(), u.getNombre(), u.getEmail(), true, u.getRol()));

        if (guiaRepository.count() > 0) {
            return;
        }

        // Quince direcciones reales repartidas por todo Santiago: es lo que hace que la ruta
        // optimizada tenga algo que resolver. Con paradas de un solo sector, cualquier orden
        // da lo mismo y no se ve si el optimizador hace su trabajo. Los comentarios imitan lo
        // que escribe el vendedor en el Comments de SAP: unos traen horario, otros solo una
        // instrucción, y varios vienen vacíos.
        guia(90001, 187350L, "Ferretería Demo Uno SpA", "San Nicolás 630, San Miguel, Santiago", "Recibe de 9 a 13 hrs");
        // Con pie del documento, que es de donde salen de verdad la dirección y el horario.
        // Tres formas distintas, calcadas de guías reales, porque no hay plantilla: etiquetas
        // con dos puntos y saltos de línea, etiquetas sueltas sin dos puntos, y todo de
        // corrido en un párrafo. La dirección de esta primera la manda el pie, no la ficha.
        guia(90002, 187351L, "Comercial Demo Dos Ltda.", "Av. Vicuña Mackenna 2289, San Joaquín, Santiago", null,
                "DESPACHAR A:\t\rAv. Salvador 1150, Providencia, Santiago.\t\rCONTACTO:\t\rPatricia Rojas"
                        + "\t\rCEL: +56 9 8370 8082\t\rHORARIO: LUNES A VIERNES 08:30 A 17:00 HORAS.");
        guia(90003, 187352L, "Distribuidora Demo Tres", "Av. Providencia 1208, Providencia, Santiago",
                "Llamar antes, portón por calle lateral");
        guia(90004, 187353L, "Constructora Demo Cuatro", "Av. Pajaritos 3050, Maipú, Santiago", "Solo en la tarde");
        // El pie nombra un lugar, no una dirección ("BODEGA Central" no se puede geocodificar):
        // la guía se queda con la de la ficha y bodega la ve marcada para confirmarla. El
        // horario de colación es el rato en que NO reciben: no tiene que salir como ventana.
        guia(90005, 187354L, "Almacén Demo Cinco", "Gran Avenida 5460, San Miguel, Santiago", null,
                "Despachar a BODEGA Central \nHorario colación 13 a 15Hrs\nAt. Sr. Juan Mora Cel.: +56 9 8573 0973");
        guia(90006, 187355L, "Importadora Demo Seis SpA", "Av. Américo Vespucio 1001, Quilicura, Santiago",
                "Recibe hasta las 12:00");
        guia(90007, 187356L, "Comercial Demo Siete", "Av. Libertador Bernardo O'Higgins 4620, Estación Central, Santiago",
                null);
        guia(90008, 187357L, "Servicios Demo Ocho Ltda.", "Av. Departamental 1420, La Florida, Santiago",
                "Entregar entre 14 y 18 hrs, preguntar por bodega");
        guia(90009, 187358L, "Ferretería Demo Nueve", "Av. Recoleta 3020, Recoleta, Santiago",
                "Solo en la mañana, cierran a las 13:30");
        // Pie escrito de corrido, sin un solo salto de línea y sin dos puntos: cortar por
        // líneas no encontraría nada. Lo de la forma de pago no se descarta — el repartidor
        // tiene que saber que vuelve con un cheque.
        guia(90010, 187359L, "Distribuidora Demo Diez", "Av. Santa Rosa 7890, La Granja, Santiago", null,
                "CHEQUE A 30 DIAS CON CONTRAENTREGA. DESPACHAR A Av. Santa Rosa 7890, La Granja, Santiago. "
                        + "CONTACTO MATIAS CANIU TELÉFONO +569 6673 3121");
        guia(90011, 187360L, "Comercial Demo Once SpA", "Av. Las Condes 9310, Las Condes, Santiago",
                "Horario de recepción 10:00 a 17:00");
        guia(90012, 187361L, "Oficinas Demo Doce", "Av. Apoquindo 6410, Las Condes, Santiago",
                "Coordinar con el guardia del subterráneo");
        guia(90013, 187362L, "Almacén Demo Trece", "Av. Grecia 8735, Peñalolén, Santiago",
                "No reciben después de las 16 hrs");
        guia(90014, 187363L, "Comercial Demo Catorce", "Av. Independencia 2350, Independencia, Santiago", null);
        guia(90015, 187364L, "Bodegas Demo Quince Ltda.", "Av. Lo Espejo 1501, Maipú, Santiago",
                "Retiro por andén 3, recibe de 8 a 11");

        log.info("Perfil local: se cargaron {} guías y {} repartidores de prueba",
                guiaRepository.count(), authConfig.getUsuarios().size());
    }

    private void guia(int docEntry, long folio, String cliente, String direccion, String comentario) {
        guia(docEntry, folio, cliente, direccion, comentario, null);
    }

    /**
     * Con pie del documento, como llegan las guías reales.
     *
     * <p>El pie se parte con el mismo {@link FooterDespacho} que usa la sincronización, así
     * lo que se ve en local es lo que va a pasar en producción y no una imitación escrita a
     * mano que puede quedar desalineada.
     */
    private void guia(int docEntry, long folio, String cliente, String direccion, String comentario, String pie) {
        Guia guia = new Guia(docEntry, folio, cliente, direccion);
        guia.setComentario(comentario);
        if (pie != null) {
            FooterDespacho footer = FooterDespacho.de(pie);
            guia.setFooter(pie);
            guia.setDireccionFooter(enBlancoANull(footer.direccion()));
            guia.setHorarioFooter(enBlancoANull(footer.horario()));
            if (footer.direccionUbicable()) {
                guia.setDireccion(footer.direccion());
                guia.setOrigenDireccion(OrigenDireccion.FOOTER);
            } else {
                guia.setOrigenDireccion(OrigenDireccion.LOGISTICA);
            }
        }
        guiaRepository.save(guia);
    }

    private static String enBlancoANull(String texto) {
        return texto.isEmpty() ? null : texto;
    }
}
