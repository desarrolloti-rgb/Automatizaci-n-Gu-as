package com.calimport.guias.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.calimport.guias.model.Guia;
import com.calimport.guias.repository.GuiaRepository;
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
        guia(90002, 187351L, "Comercial Demo Dos Ltda.", "Av. Vicuña Mackenna 2289, San Joaquín, Santiago", null);
        guia(90003, 187352L, "Distribuidora Demo Tres", "Av. Providencia 1208, Providencia, Santiago",
                "Llamar antes, portón por calle lateral");
        guia(90004, 187353L, "Constructora Demo Cuatro", "Av. Pajaritos 3050, Maipú, Santiago", "Solo en la tarde");
        guia(90005, 187354L, "Almacén Demo Cinco", "Gran Avenida 5460, San Miguel, Santiago", null);
        guia(90006, 187355L, "Importadora Demo Seis SpA", "Av. Américo Vespucio 1001, Quilicura, Santiago",
                "Recibe hasta las 12:00");
        guia(90007, 187356L, "Comercial Demo Siete", "Av. Libertador Bernardo O'Higgins 4620, Estación Central, Santiago",
                null);
        guia(90008, 187357L, "Servicios Demo Ocho Ltda.", "Av. Departamental 1420, La Florida, Santiago",
                "Entregar entre 14 y 18 hrs, preguntar por bodega");
        guia(90009, 187358L, "Ferretería Demo Nueve", "Av. Recoleta 3020, Recoleta, Santiago",
                "Solo en la mañana, cierran a las 13:30");
        guia(90010, 187359L, "Distribuidora Demo Diez", "Av. Santa Rosa 7890, La Granja, Santiago", null);
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
        Guia guia = new Guia(docEntry, folio, cliente, direccion);
        guia.setComentario(comentario);
        guiaRepository.save(guia);
    }
}
