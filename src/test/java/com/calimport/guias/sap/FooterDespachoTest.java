package com.calimport.guias.sap;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FooterDespachoTest {

    /**
     * El pie del DocEntry 46209 tal como lo devuelve el Service Layer, con los separadores
     * reales: tabulacion y retorno de carro, sin salto de linea.
     */
    private static final String REAL = "DESPACHAR A:\t\rAV.BERLIN PARCELA 34 C, COLONIA ALEMANA, PEÑAFLOR."
            + "\t\rCONTACTO:\t\rJUAN ELIAS ESCUDERO\t\rCEL: +56 9 8370 8082"
            + "\t\rHORARIO: LUNES A VIERNES 08:30 A 17:00 HORAS.";

    @Test
    void separaElPieRealDeUnaGuiaEnSusSecciones() {
        FooterDespacho footer = FooterDespacho.de(REAL);

        assertEquals("AV.BERLIN PARCELA 34 C, COLONIA ALEMANA, PEÑAFLOR.", footer.direccion());
        assertEquals("JUAN ELIAS ESCUDERO", footer.contacto());
        assertEquals("+56 9 8370 8082", footer.telefono());
        assertEquals("LUNES A VIERNES 08:30 A 17:00 HORAS.", footer.horario());
        assertEquals("", footer.transportista());
        assertEquals("", footer.otros());
        assertTrue(footer.direccionUbicable());
        assertFalse(footer.vacio());
    }

    @Test
    void lasEtiquetasNoNecesitanDosPuntosNiLineaPropia() {
        // Pie real: "Despachar a" y "Horario" sin dos puntos, "At." abreviado y "Cel.:" en
        // medio de la misma linea que el nombre.
        FooterDespacho footer = FooterDespacho.de(
                "Despachar a BODEGA Fruna \nHorario colación 13 a 15Hrs\nAt. Sr. Juan Mora Cel.: +56 9 8573 0973");

        assertEquals("BODEGA Fruna", footer.direccion());
        assertEquals("colación 13 a 15Hrs", footer.horario());
        assertEquals("Sr. Juan Mora", footer.contacto());
        assertEquals("+56 9 8573 0973", footer.telefono());
    }

    @Test
    void unNombreDeLugarNoSeUsaComoDireccion() {
        // "BODEGA Fruna" no tiene numero: no ubica nada en el mapa. Cambiarla por la
        // direccion de la ficha del cliente seria cambiar un dato bueno por uno inservible.
        assertFalse(FooterDespacho.de("Despachar a BODEGA Fruna").direccionUbicable());
        assertTrue(FooterDespacho.de("DESPACHAR A FRANCISCO DE CAMARGO 14317, SAN BERNARDO, RM.")
                .direccionUbicable());
    }

    @Test
    void separaUnPieEscritoDeCorridoEnUnSoloParrafo() {
        // Pie real, sin un solo salto de linea: cortar por lineas no encontraria nada.
        FooterDespacho footer = FooterDespacho.de("CHEQUE A 30 DIAS CON CONTRAENTREGA. DESPACHAR A "
                + "FRANCISCO DE CAMARGO 14317, SAN BERNARDO, RM. CONTACTO MATIAS CANIU "
                + "TELÉFONO +569 6673 3121");

        assertEquals("FRANCISCO DE CAMARGO 14317, SAN BERNARDO, RM.", footer.direccion());
        assertEquals("MATIAS CANIU", footer.contacto());
        assertEquals("+569 6673 3121", footer.telefono());
        // Que lo paguen con cheque contra entrega le importa al repartidor: no se descarta.
        assertEquals("CHEQUE A 30 DIAS CON CONTRAENTREGA.", footer.otros());
    }

    @Test
    void reconoceElTransportistaCuandoLaGuiaNoLaRepartCalimport() {
        // Pie real: va por SAMEX a Calama. Ningun repartidor de Santiago la lleva.
        FooterDespacho footer = FooterDespacho.de("PAGADO (TRANSFERENCIA) DESPACHAR  VIA SAMEX A "
                + "Condominio Verdes Campiñas 3179, Pasaje 5 casa 34, Calama. - CONTACTO: ROBERTO FERNANDEZ "
                + "- TELEFONO: 569 5060 6296");

        assertEquals("SAMEX", footer.transportista());
        assertEquals("Condominio Verdes Campiñas 3179, Pasaje 5 casa 34, Calama.", footer.direccion());
        assertEquals("ROBERTO FERNANDEZ", footer.contacto());
        assertEquals("569 5060 6296", footer.telefono());
        assertEquals("PAGADO (TRANSFERENCIA)", footer.otros());
    }

    @Test
    void elHorarioTerminaDondeDejaDeHablarDeHoras() {
        // Pie real de la guía 296475: el contacto viene pegado detrás del horario y sin
        // etiqueta propia, así que el corte por etiquetas no tiene dónde cortar.
        FooterDespacho footer = FooterDespacho.de("Despachar A Bodega Planta, SITE CPP (Promedio) KM. 63 "
                + "LONGITUDINAL SUR - SAN FRANCISCO MOSTAZAL Horario de recepción Lunes a Viernes de 8:30 a 13:00 "
                + "horas Pedro Zarate +569 6845 9569");

        assertEquals("Bodega Planta, SITE CPP (Promedio) KM. 63 LONGITUDINAL SUR - SAN FRANCISCO MOSTAZAL",
                footer.direccion());
        assertEquals("Lunes a Viernes de 8:30 a 13:00 horas", footer.horario());
        assertEquals("Pedro Zarate +569 6845 9569", footer.otros());
        assertTrue(footer.direccionUbicable());
    }

    @Test
    void unTelefonoDetrasDelHorarioNoSeLeeComoUnaHora() {
        // "+569 6845 9569" no tiene dos puntos ni dice "horas": no es una hora.
        FooterDespacho footer = FooterDespacho.de("HORARIO: 9 a 18 hrs Juan +569 6845 9569");

        assertEquals("9 a 18 hrs", footer.horario());
        assertEquals("Juan +569 6845 9569", footer.otros());
    }

    @Test
    void unHorarioSinHoraReconocibleSeDejaEntero() {
        // Cortarlo por adivinanza sería peor que dejarlo como está.
        assertEquals("a convenir con el encargado",
                FooterDespacho.de("HORARIO: a convenir con el encargado").horario());
    }

    @Test
    void reconoceLasGuiasQueRetiraElCliente() {
        // Las cuatro formas vistas en produccion. Ninguna tiene marcada la forma de envio
        // en SAP: el unico lugar donde consta es el texto.
        for (String pie : new String[] {
                "RETIRA EN SUCURSAL",
                "CLIENTE RETIRA EN SUCURSAL",
                "RETIRA EN SUCURSAL ANTOFAGASTA",
                "MATERIAL SERA RETIRADO POR EL CLIENTE"}) {
            assertTrue(FooterDespacho.de(pie).esRetiroEnLocal(), "deberia ser retiro: " + pie);
        }
    }

    @Test
    void unDespachoNormalNoSeConfundeConUnRetiro() {
        for (String pie : new String[] {
                "DESPACHAR A: AV.BERLIN PARCELA 34 C, PEÑAFLOR.",
                "DESPACHAR A LO ESPEJO 1300, MAIPU, RM",
                "PAGADO (TRANSFERENCIA) DESPACHAR VIA SAMEX A Calama"}) {
            assertFalse(FooterDespacho.de(pie).esRetiroEnLocal(), "no deberia ser retiro: " + pie);
        }
    }

    @Test
    void despacharAConDireccionEsDespachoAunqueLaCalleDigaRetiro() {
        // Folio 233 de CALIMPORT_TEST, tal cual: se saltaba como retiro en local por la calle.
        // Regla de bodega: si dice a dónde despachar, es despacho.
        FooterDespacho pie = FooterDespacho.de("DESPACHAR A:\rDirección: AV. EL RETIRO 1250, Comuna: Renca, "
                + "Ciudad: Santiago, Horario de Atención: 1-2 PM, CONTACTO: BERNARDO PEREZ, FONO: 569969696");

        assertFalse(pie.esRetiroEnLocal());
        assertEquals("AV. EL RETIRO 1250, Renca, Santiago", pie.direccion());
    }

    @Test
    void despacharAEscritoAManoConUnaCalleRetiroTambienEsDespacho() {
        assertFalse(FooterDespacho.de("DESPACHAR A VILLA EL RETIRO 455, QUILICURA").esRetiroEnLocal());
    }

    @Test
    void despacharASinUnaDireccionNoAlcanzaParaDescartarElRetiro() {
        // Sin dirección que ubicar, "DESPACHAR A" no dice a dónde: manda lo que diga el resto.
        assertTrue(FooterDespacho.de("DESPACHAR A: RETIRA EN SUCURSAL").esRetiroEnLocal());
    }

    @Test
    void unPieVacioNoEsRetiro() {
        // Si el documento no dice nada, se despacha: dejar de importar una guia por silencio
        // seria peor que importar una de mas.
        assertFalse(FooterDespacho.de("").esRetiroEnLocal());
        assertFalse(FooterDespacho.de(null).esRetiroEnLocal());
    }

    @Test
    void laEtiquetaValeConElValorAbajoOEnLaMismaLinea() {
        // "DESPACHAR A:" trae la direccion en la linea siguiente; "HORARIO:" la trae al lado.
        // Las dos formas conviven en el mismo documento, asi que las dos tienen que andar.
        FooterDespacho footer = FooterDespacho.de("DESPACHAR A:\t\rCALLE UNO 123, MAIPU\t\rHORARIO: 9 A 18");

        assertEquals("CALLE UNO 123, MAIPU", footer.direccion());
        assertEquals("9 A 18", footer.horario());
    }

    @Test
    void variasLineasDeUnaSeccionSeJuntanEnUna() {
        FooterDespacho footer = FooterDespacho.de("DESPACHAR A:\rCALLE UNO 123\rDEPTO 402\rÑUÑOA");

        assertEquals("CALLE UNO 123 DEPTO 402 ÑUÑOA", footer.direccion());
    }

    @Test
    void laEtiquetaSeReconoceSinTildesNiPuntosYEnCualquierCaja() {
        FooterDespacho footer = FooterDespacho.de("Dirección: Calle Uno 123, Maipú\rTel.: 22 123 4567");

        assertEquals("Calle Uno 123, Maipú", footer.direccion());
        assertEquals("22 123 4567", footer.telefono());
    }

    @Test
    void unaEtiquetaDesconocidaNoSePierde() {
        // Nadie sigue una plantilla: lo que no se sabe clasificar se suma a la seccion en
        // curso, porque alguien lo escribio para que el repartidor lo leyera.
        FooterDespacho footer = FooterDespacho.de("CONTACTO:\rJUANA\rPORTERIA: dejar con el conserje");

        assertEquals("JUANA PORTERIA: dejar con el conserje", footer.contacto());
    }

    @Test
    void elTextoAntesDeLaPrimeraEtiquetaQuedaEnOtros() {
        FooterDespacho footer = FooterDespacho.de("RETIRA EL CLIENTE\rHORARIO: hasta las 12");

        assertEquals("RETIRA EL CLIENTE", footer.otros());
        assertEquals("hasta las 12", footer.horario());
        assertEquals("", footer.direccion());
    }

    @Test
    void unPieSinEtiquetasNoSeDescarta() {
        FooterDespacho footer = FooterDespacho.de("Llamar antes de llegar");

        assertEquals("Llamar antes de llegar", footer.otros());
        assertEquals("", footer.direccion());
        assertFalse(footer.vacio());
    }

    @Test
    void unPieVacioONuloDevuelveTodoEnBlanco() {
        for (String texto : new String[] {null, "", "   ", "\t\r\t\r"}) {
            FooterDespacho footer = FooterDespacho.de(texto);
            assertTrue(footer.vacio(), "deberia estar vacio: " + texto);
            assertEquals("", footer.direccion());
            assertEquals("", footer.horario());
        }
    }

    @Test
    void unaHoraConDosPuntosNoSeConfundeConUnaEtiqueta() {
        FooterDespacho footer = FooterDespacho.de("HORARIO:\r08:30 A 17:00");

        assertEquals("08:30 A 17:00", footer.horario());
        assertEquals("", footer.otros());
    }

    /**
     * La plantilla de las guías nuevas (desde noviembre de 2026), con la forma exacta del
     * DocEntry 46296 de CALIMPORT_TEST: el "DESPACHAR A:" en su línea y después todo en una
     * sola, separado por comas. El contacto y el teléfono son inventados.
     */
    private static final String PLANTILLA = "DESPACHAR A:\rDirección: AV. SAN IGNACIO 1200, Comuna: Quilicura, "
            + "Ciudad: Santiago, Horario de Atención: 09:00 - 19:00, CONTACTO: JUAN PÉREZ, FONO: 56912345678";

    @Test
    void separaLaPlantillaDeLasGuiasNuevas() {
        FooterDespacho footer = FooterDespacho.de(PLANTILLA);

        // La comuna y la ciudad vuelven a la dirección, sin sus etiquetas: es la forma
        // "calle número, comuna, ciudad" que entiende el geocodificador.
        assertEquals("AV. SAN IGNACIO 1200, Quilicura, Santiago", footer.direccion());
        assertEquals("09:00 - 19:00", footer.horario());
        assertEquals("JUAN PÉREZ", footer.contacto());
        assertEquals("56912345678", footer.telefono());
        assertEquals("", footer.transportista());
        assertEquals("", footer.otros());
        assertTrue(footer.direccionUbicable());
    }

    @Test
    void elTransporteDeLaPlantillaEsElTransportista() {
        // Forma del DocEntry 46293 de CALIMPORT_TEST: el transporte va al final, después
        // del teléfono, y sin su etiqueta quedaba pegado al número.
        FooterDespacho footer = FooterDespacho.de("DESPACHAR A:\rDirección: los manzanos 5020, Comuna: San Joaquín, "
                + "Ciudad: Santiago, Horario de Atención: 10-11 AM, CONTACTO: ANA SOTO, FONO: 912345678, "
                + "TRANSPORTE: SAMEX");

        assertEquals("los manzanos 5020, San Joaquín, Santiago", footer.direccion());
        assertEquals("10-11 AM", footer.horario());
        assertEquals("912345678", footer.telefono());
        assertEquals("SAMEX", footer.transportista());
    }

    @Test
    void elHorarioDeAtencionNoSeLeeComoElContacto() {
        // "Atención" sola es el contacto ("ATENCIÓN: Sr. Mora"); detrás de "Horario de" es
        // la ventana de recepción. Partirlo ahí dejaba el horario en "de" y la hora en el
        // contacto.
        FooterDespacho footer = FooterDespacho.de("Horario de atención: lunes a viernes 9:00 a 17:30 hrs. "
                + "ATENCIÓN: Sr. Mora");

        assertEquals("lunes a viernes 9:00 a 17:30 hrs.", footer.horario());
        assertEquals("Sr. Mora", footer.contacto());
    }

    @Test
    void laComunaOLaCiudadQueYaVienenEnLaDireccionNoSeRepiten() {
        assertEquals("CALLE UNO 123, MAIPÚ, Santiago",
                FooterDespacho.de("Dirección: CALLE UNO 123, MAIPÚ, Comuna: Maipu, Ciudad: Santiago").direccion());
        assertEquals("Calle Dos 456, Santiago",
                FooterDespacho.de("Dirección: Calle Dos 456, Comuna: Santiago, Ciudad: Santiago").direccion());
    }

    @Test
    void laPlantillaSinLlenarNoInventaNada() {
        // Sin dirección en el pie la guía se queda con la de la ficha del cliente: los
        // separadores de la plantilla no pueden pasar por una dirección.
        FooterDespacho footer = FooterDespacho.de("DESPACHAR A:\rDirección: , Comuna: , Ciudad: , "
                + "Horario de Atención:  - , CONTACTO: , FONO: ");

        assertEquals("", footer.direccion());
        assertEquals("", footer.horario());
        assertTrue(footer.vacio());
    }

    @Test
    void unRetiroEscritoEnElTransporteTambienEsRetiro() {
        assertTrue(FooterDespacho.de("DESPACHAR A:\rDirección: , Comuna: , Ciudad: , Horario de Atención: , "
                + "CONTACTO: ANA, FONO: 912345678, TRANSPORTE: RETIRA CLIENTE").esRetiroEnLocal());
    }

    @Test
    void reconoceLasGuiasDeUnTransportistaExterno() {
        List<String> externos = List.of("SAMEX");

        // Las dos formas: el campo de la plantilla y la frase de los pies escritos a mano.
        assertTrue(FooterDespacho.de("CONTACTO: ANA, FONO: 912345678, TRANSPORTE: SAMEX").vaPorTransportista(externos));
        assertTrue(FooterDespacho.de("DESPACHAR VIA Samex A Calle Uno 123, Calama").vaPorTransportista(externos));
        // Un transporte que no está en la lista no es externo: puede ser el de Calimport.
        assertFalse(FooterDespacho.de("TRANSPORTE: CALIMPORT").vaPorTransportista(externos));
        assertFalse(FooterDespacho.de("DESPACHAR A: CALLE UNO 123, MAIPU").vaPorTransportista(externos));
        // Con la lista vacía no hay externos: es como se apaga el filtro.
        assertFalse(FooterDespacho.de("TRANSPORTE: SAMEX").vaPorTransportista(List.of()));
    }

    @Test
    void unaPalabraQueEmpiezaComoEtiquetaNoEsUnaEtiqueta() {
        // "TELÉFONO" empieza con "TEL" y "HORAS" con "HORA": partir ahi dejaria el resto de
        // la palabra como valor.
        FooterDespacho footer = FooterDespacho.de("HORARIO: 9 A 18 HORAS\rTELÉFONO: 22 123 4567");

        assertEquals("9 A 18 HORAS", footer.horario());
        assertEquals("22 123 4567", footer.telefono());
    }
}
