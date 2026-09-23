package com.calimport.guias.sap;

import java.util.EnumMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * El pie de la guía de despacho de SAP —{@code T0.[Footer]} en una consulta, que el Service
 * Layer publica como {@code ClosingRemarks}— partido en secciones.
 *
 * <p>Es donde el vendedor escribe de verdad a dónde va la mercadería, quién la recibe y a
 * qué hora. <b>No hay plantilla</b>: cada uno lo escribe a su manera, y de cuatro pies
 * reales no hay dos con el mismo formato.
 *
 * <pre>
 * DESPACHAR A:⇥␍AV.BERLIN PARCELA 34 C, COLONIA ALEMANA, PEÑAFLOR.⇥␍CONTACTO:⇥␍JUAN ELIAS
 * ESCUDERO⇥␍CEL: +56 9 8370 8082⇥␍HORARIO: LUNES A VIERNES 08:30 A 17:00 HORAS.
 *
 * Despachar a BODEGA Fruna ⏎Horario colación 13 a 15Hrs⏎At. Sr. Juan Mora Cel.: +56 9 ...
 *
 * CHEQUE A 30 DIAS CON CONTRAENTREGA. DESPACHAR A FRANCISCO DE CAMARGO 14317, SAN BERNARDO,
 * RM. CONTACTO MATIAS CANIU TELÉFONO +569 6673 3121
 *
 * PAGADO (TRANSFERENCIA) DESPACHAR  VIA SAMEX A Condominio Verdes Campiñas 3179, Pasaje 5
 * casa 34, Calama. - CONTACTO: ROBERTO FERNANDEZ - TELEFONO: 569 5060 6296
 * </pre>
 *
 * <p>De ahí salen las reglas de este parser, cada una por un caso real:
 *
 * <ul>
 *   <li><b>Las etiquetas se buscan en todo el texto, no por línea.</b> El tercer pie viene
 *       entero en un párrafo sin un solo salto, así que cortar por líneas no encuentra nada.
 *   <li><b>Los dos puntos son opcionales.</b> "CONTACTO MATIAS CANIU" y "Horario colación"
 *       no los llevan.
 *   <li><b>El valor es todo lo que va hasta la etiqueta siguiente</b>, venga al lado
 *       ({@code HORARIO: 08:30 a 17:00}) o en las líneas de abajo ({@code DESPACHAR A:}).
 *   <li>Lo que está <b>antes de la primera etiqueta</b> —condiciones de pago, casi siempre—
 *       queda en {@link #otros}. "CHEQUE A 30 DÍAS CON CONTRAENTREGA" le importa al
 *       repartidor, que va a tener que volver con un cheque.
 *   <li><b>"DESPACHAR VÍA SAMEX A …"</b> deja el transportista en {@link #transportista}:
 *       esa guía no la lleva un repartidor de Calimport, y su dirección puede estar en
 *       cualquier parte del país.
 * </ul>
 *
 * <p>Nada de esto es obligatorio. Un pie vacío, o escrito sin ninguna etiqueta conocida,
 * devuelve todo en blanco salvo {@link #otros}, y la sincronización se queda con los campos
 * de siempre ({@code Address2} y {@code Comments}).
 */
public record FooterDespacho(String direccion, String contacto, String telefono, String horario,
                             String transportista, String otros) {

    private enum Seccion {
        DIRECCION, CONTACTO, TELEFONO, HORARIO, OTROS
    }

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** Que la etiqueta no sea el pedazo de una palabra más larga: "TEL" dentro de "TELÉFONO". */
    private static final String FIN = "(?![\\p{L}])";

    /**
     * Las etiquetas vistas en pies reales, <b>en orden</b>: la primera que calza gana, así
     * que las largas van antes que las cortas ({@code TELÉFONO} antes que {@code TEL.},
     * {@code DESPACHAR VÍA X A} antes que {@code DESPACHO}).
     */
    private record Etiqueta(String patron, Seccion seccion) {

        Pattern compilada() {
            return Pattern.compile(patron, FLAGS);
        }
    }

    private static final List<Etiqueta> ETIQUETAS = List.of(
            new Etiqueta("DESPACH(?:AR|O)(?:\\s+V[IÍ]A\\s+[\\p{L}\\p{N}.&\\-]+(?:\\s+[\\p{L}\\p{N}.&\\-]+)??)?"
                    + "\\s+(?:AL?|EN)" + FIN, Seccion.DIRECCION),
            new Etiqueta("DIRECCI[OÓ]N(?:\\s+DE\\s+DESPACHO)?" + FIN, Seccion.DIRECCION),
            new Etiqueta("ENTREGAR\\s+EN" + FIN, Seccion.DIRECCION),
            // Palabras que también aparecen en medio de una frase ("dejar en recepción",
            // "coordinar con el encargado"): solo valen como etiqueta si traen sus dos
            // puntos. Sin eso parten el texto en dos donde no hay nada que partir.
            new Etiqueta("DESPACHO\\s*:", Seccion.DIRECCION),
            new Etiqueta("DIR\\." + FIN, Seccion.DIRECCION),
            new Etiqueta("CONTACTOS?" + FIN, Seccion.CONTACTO),
            new Etiqueta("ATENCI[OÓ]N" + FIN, Seccion.CONTACTO),
            new Etiqueta("ENCARGADO\\s*:", Seccion.CONTACTO),
            new Etiqueta("AT\\." + FIN, Seccion.CONTACTO),
            new Etiqueta("TEL[EÉ]FONOS?" + FIN, Seccion.TELEFONO),
            new Etiqueta("CEL(?:ULAR)?" + FIN, Seccion.TELEFONO),
            new Etiqueta("TELS?\\." + FIN, Seccion.TELEFONO),
            new Etiqueta("FONOS?" + FIN, Seccion.TELEFONO),
            new Etiqueta("HORARIOS?\\s+DE\\s+RECEPCI[OÓ]N" + FIN, Seccion.HORARIO),
            new Etiqueta("HORARIOS?" + FIN, Seccion.HORARIO),
            new Etiqueta("RECEPCI[OÓ]N\\s*:", Seccion.HORARIO));

    private static final Pattern CUALQUIER_ETIQUETA = Pattern.compile(
            "(?<![\\p{L}])(?:" + ETIQUETAS.stream().map(Etiqueta::patron).collect(Collectors.joining("|")) + ")",
            FLAGS);

    /** "DESPACHAR VÍA SAMEX A": el nombre del transportista queda en el grupo 1. */
    private static final Pattern DESPACHO_VIA = Pattern.compile(
            "DESPACH(?:AR|O)\\s+V[IÍ]A\\s+(.+?)\\s+(?:AL?|EN)$", FLAGS);

    /** Basura de separación: los dos puntos de la etiqueta, guiones y comas entre secciones. */
    private static final Pattern BORDE_IZQUIERDO = Pattern.compile("^[\\s:\\-–·.,;]+");
    private static final Pattern BORDE_DERECHO = Pattern.compile("[\\s\\-–·,;]+$");

    /**
     * Una hora, con el "hrs"/"horas" que la suele acompañar. Marca dónde <b>termina</b> de
     * hablar de horario la sección del horario.
     *
     * <p>Exige dos puntos o la palabra "horas" a propósito: un número suelto no es una hora.
     * Sin eso, "+569 6845 9569" se leería como parte del horario.
     */
    private static final Pattern EXPRESION_HORARIA = Pattern.compile(
            "\\d{1,2}[:.]\\d{2}(?:\\s*(?:hrs?|horas)\\.?)?|\\d{1,2}\\s*(?:hrs?|horas)\\.?", FLAGS);

    public static FooterDespacho de(String texto) {
        EnumMap<Seccion, StringBuilder> partes = new EnumMap<>(Seccion.class);
        String transportista = "";

        if (texto != null && !texto.isBlank()) {
            // Antes de la primera etiqueta no se sabe de qué se está hablando; va a "otros",
            // que igual termina en el comentario que lee el repartidor.
            Seccion actual = Seccion.OTROS;
            int desde = 0;

            Matcher m = CUALQUIER_ETIQUETA.matcher(texto);
            while (m.find()) {
                agregar(partes, actual, valor(texto.substring(desde, m.start())));
                actual = seccionDe(m.group());
                if (transportista.isEmpty()) {
                    transportista = transportistaDe(m.group());
                }
                desde = m.end();
            }
            agregar(partes, actual, valor(texto.substring(desde)));
            recortarHorario(partes);
        }

        return new FooterDespacho(
                contenido(partes, Seccion.DIRECCION),
                contenido(partes, Seccion.CONTACTO),
                contenido(partes, Seccion.TELEFONO),
                contenido(partes, Seccion.HORARIO),
                transportista,
                contenido(partes, Seccion.OTROS));
    }

    /** Si el pie no aportó nada que la app pueda usar. */
    public boolean vacio() {
        return direccion.isEmpty() && contacto.isEmpty() && telefono.isEmpty()
                && horario.isEmpty() && transportista.isEmpty() && otros.isEmpty();
    }

    /**
     * Si lo que dice "DESPACHAR A" sirve para ubicar un punto en el mapa.
     *
     * <p>La prueba es que tenga un número. "FRANCISCO DE CAMARGO 14317, SAN BERNARDO" es una
     * dirección; "BODEGA Fruna" es el nombre de un lugar, y darle eso al geocodificador en
     * vez de la dirección que sí está en la ficha del cliente sería cambiar un dato bueno por
     * uno que no se puede ubicar.
     */
    public boolean direccionUbicable() {
        return direccion.chars().anyMatch(Character::isDigit);
    }

    private static Seccion seccionDe(String etiqueta) {
        for (Etiqueta candidata : ETIQUETAS) {
            if (candidata.compilada().matcher(etiqueta).matches()) {
                return candidata.seccion();
            }
        }
        return Seccion.OTROS;
    }

    private static String transportistaDe(String etiqueta) {
        Matcher m = DESPACHO_VIA.matcher(etiqueta);
        return m.matches() ? m.group(1).trim() : "";
    }

    /**
     * SAP separa con tabulación y retorno de carro sueltos ({@code \t\r}, sin {@code \n}):
     * tal cual, en el celular se ve todo pegoteado. Se aplanan a espacios y se sacan los
     * bordes que dejó la etiqueta o el separador de la sección anterior.
     */
    private static String valor(String bruto) {
        String plano = bruto.replaceAll("[\\t\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        return BORDE_DERECHO.matcher(BORDE_IZQUIERDO.matcher(plano).replaceAll("")).replaceAll("");
    }

    /**
     * El horario termina donde deja de hablar de horas; lo que sigue se va a {@link #otros}.
     *
     * <p>Hace falta porque el nombre y el teléfono de quien recibe suelen venir pegados
     * detrás del horario <b>sin etiqueta propia</b>, y ahí el corte por etiquetas no tiene
     * dónde cortar. Del pie de la guía 296475, tal cual: "Horario de recepción Lunes a
     * Viernes de 8:30 a 13:00 horas Pedro Zarate +569 6845 9569" — el horario es hasta
     * "13:00 horas", el resto es el contacto.
     *
     * <p>Si la sección no tiene ninguna hora reconocible se deja entera: cortarla por
     * adivinanza sería peor que dejarla como está.
     */
    private static void recortarHorario(EnumMap<Seccion, StringBuilder> partes) {
        String horario = contenido(partes, Seccion.HORARIO);
        if (horario.isEmpty()) {
            return;
        }
        int fin = -1;
        Matcher m = EXPRESION_HORARIA.matcher(horario);
        while (m.find()) {
            fin = m.end();
        }
        if (fin < 0 || fin >= horario.length()) {
            return;
        }
        partes.put(Seccion.HORARIO, new StringBuilder(valor(horario.substring(0, fin))));
        agregar(partes, Seccion.OTROS, valor(horario.substring(fin)));
    }

    private static void agregar(EnumMap<Seccion, StringBuilder> partes, Seccion seccion, String valor) {
        if (valor.isEmpty()) {
            return;
        }
        StringBuilder acumulado = partes.computeIfAbsent(seccion, s -> new StringBuilder());
        if (!acumulado.isEmpty()) {
            acumulado.append(' ');
        }
        acumulado.append(valor);
    }

    private static String contenido(EnumMap<Seccion, StringBuilder> partes, Seccion seccion) {
        StringBuilder acumulado = partes.get(seccion);
        return acumulado == null ? "" : acumulado.toString();
    }
}
