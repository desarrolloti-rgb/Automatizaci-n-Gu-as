package com.calimport.guias.service;

import java.time.LocalTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Lee el horario del comentario con patrones fijos, sin IA ni costo. Es el que se usa por
 * defecto ({@code rutas.comentarios=reglas}).
 *
 * <p>Entiende las formas habituales: "de 9 a 13", "entre 14 y 18 hrs", "10:00 a 17:00",
 * "hasta las 12", "cierran a las 13:30", "no reciben después de las 16", "desde las 15",
 * "solo en la mañana" (09:00-13:00) y "en la tarde" (14:00-18:00). Una hora de 1 a 7 sin
 * "am" se lee como de la tarde: nadie recibe mercadería a las 3 de la madrugada.
 *
 * <p>Si no encuentra nada, devuelve todo en null y la guía queda sin horario: preferible a
 * inventar una restricción. No arma nota: el comentario completo ya le llega al repartidor.
 * Con dos rangos ("de 9 a 13 y de 15 a 18") toma el primero; eso lo corrige bodega con
 * "Editar horario".
 */
@Service
@ConditionalOnProperty(prefix = "rutas", name = "comentarios", havingValue = "reglas", matchIfMissing = true)
public class InterpreteReglas implements InterpreteComentarios {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;

    /** Grupos: hora, minutos, am/pm. No empieza ni termina en medio de otro número. */
    private static final String HORA =
            "(?<![\\d:.])(\\d{1,2})(?:[:.](\\d{2}))?(?!\\d)\\s*(?:(?:hrs?|horas|h)\\b\\.?)?\\s*(?:(am|pm)\\b)?";
    private static final String SEPARADOR = "\\s*(?:-|–|\\ba\\b|\\by\\b|\\bhasta\\b)\\s*(?:las\\s+)?";
    private static final String LAS = "\\s+(?:las\\s+)?";

    private static final Pattern NO_RECIBE_DESPUES =
            Pattern.compile("\\bno\\s+(?:se\\s+)?recib\\w*\\s+(?:despu[eé]s\\s+de|pasad[ao]s?)" + LAS + HORA, FLAGS);
    private static final Pattern NO_RECIBE_ANTES =
            Pattern.compile("\\bno\\s+(?:se\\s+)?recib\\w*\\s+antes\\s+de" + LAS + HORA, FLAGS);
    private static final Pattern RANGO_CON_PREFIJO =
            Pattern.compile("\\b(?:de|entre|desde)" + LAS + HORA + SEPARADOR + HORA, FLAGS);
    /** "10:00 a 17:00": sin "de" delante, solo vale si alguna hora es inequívoca (ver horaExplicita). */
    private static final Pattern RANGO_SUELTO = Pattern.compile(HORA + SEPARADOR + HORA, FLAGS);
    private static final Pattern HASTA =
            Pattern.compile("\\b(?:hasta|antes\\s+de|cierra(?:n)?\\s+a|m[aá]ximo(?:\\s+a)?)" + LAS + HORA, FLAGS);
    private static final Pattern DESDE =
            Pattern.compile("\\b(?:desde|despu[eé]s\\s+de|a\\s+partir\\s+de|abre(?:n)?\\s+a)" + LAS + HORA, FLAGS);
    private static final Pattern MANANA =
            Pattern.compile("\\b(?:(?:en|por)\\s+la\\s+ma[ñn]ana|ma[ñn]anas|solo\\s+ma[ñn]ana|am)\\b", FLAGS);
    private static final Pattern TARDE =
            Pattern.compile("\\b(?:(?:en|por)\\s+la\\s+tarde|tardes|solo\\s+tarde|pm)\\b", FLAGS);

    private static final Pattern HORA_EXPLICITA =
            Pattern.compile("[:.]\\d{2}|\\d\\s*(?:hrs?|horas|h)\\b|\\b(?:am|pm)\\b", FLAGS);
    /**
     * Lo que va antes de una hora y la descalifica como horario de recepción.
     *
     * <p>"No reciben de 13 a 14" es la hora en que NO reciben, y "horario colación 13 a
     * 15hrs" —tal cual, del pie de una guía real— es el rato en que la bodega está cerrada.
     * Leerlas como ventana de entrega manda al repartidor justo a la hora en que no lo van
     * a atender, que es peor que no saber nada: sin ventana la guía se ordena por cercanía
     * y el horario se lee en el comentario, que viaja completo.
     */
    private static final Pattern NEGACION_PREVIA = Pattern.compile(
            "\\bno\\s+(?:se\\s+)?recib\\w*\\s*$|\\b(?:colaci[oó]n|almuerzo|cerrado)\\b[^\\d]*$", FLAGS);

    @Override
    public Interpretacion interpretar(String comentario) {
        if (comentario == null || comentario.isBlank()) {
            return new Interpretacion(null, null, null);
        }
        Lectura lectura = new Lectura(comentario.toLowerCase(Locale.ROOT));

        // Las negaciones primero: si no, "después de las 16" se leería como "desde las 16".
        LocalTime hasta = lectura.hora(NO_RECIBE_DESPUES);
        LocalTime desde = lectura.hora(NO_RECIBE_ANTES);

        LocalTime[] rango = lectura.rango(RANGO_CON_PREFIJO, false);
        if (rango == null) {
            rango = lectura.rango(RANGO_SUELTO, true);
        }
        if (rango != null) {
            desde = desde != null ? desde : rango[0];
            hasta = hasta != null ? hasta : rango[1];
        }
        if (hasta == null) {
            hasta = lectura.hora(HASTA);
        }
        if (desde == null) {
            desde = lectura.hora(DESDE);
        }

        // "Solo en la mañana, cierran a las 13:30": la hora explícita manda sobre la genérica.
        if (lectura.contiene(MANANA)) {
            desde = desde != null ? desde : LocalTime.of(9, 0);
            hasta = hasta != null ? hasta : LocalTime.of(13, 0);
        } else if (lectura.contiene(TARDE)) {
            desde = desde != null ? desde : LocalTime.of(14, 0);
            hasta = hasta != null ? hasta : LocalTime.of(18, 0);
        }

        if (desde != null && hasta != null && !desde.isBefore(hasta)) {
            return new Interpretacion(null, null, null);
        }
        return new Interpretacion(desde, hasta, null);
    }

    static LocalTime convertir(String hora, String minutos, String amPm) {
        int h = Integer.parseInt(hora);
        int m = minutos == null ? 0 : Integer.parseInt(minutos);
        if ("pm".equalsIgnoreCase(amPm) && h < 12) {
            h += 12;
        } else if (amPm == null && h >= 1 && h <= 7) {
            h += 12;
        }
        if (h > 23 || m > 59) {
            return null;
        }
        return LocalTime.of(h, m);
    }

    /** El comentario mientras se lee: lo ya usado se borra para que otro patrón no lo vuelva a leer. */
    private static final class Lectura {

        private final StringBuilder texto;

        Lectura(String texto) {
            this.texto = new StringBuilder(texto);
        }

        LocalTime hora(Pattern patron) {
            Matcher m = patron.matcher(texto);
            while (m.find()) {
                if (negado(m.start())) {
                    continue;
                }
                LocalTime hora = convertir(m.group(1), m.group(2), m.group(3));
                if (hora != null) {
                    borrar(m);
                    return hora;
                }
            }
            return null;
        }

        LocalTime[] rango(Pattern patron, boolean exigeHoraExplicita) {
            Matcher m = patron.matcher(texto);
            while (m.find()) {
                if (negado(m.start()) || (exigeHoraExplicita && !HORA_EXPLICITA.matcher(m.group()).find())) {
                    continue;
                }
                LocalTime desde = convertir(m.group(1), m.group(2), m.group(3));
                LocalTime hasta = convertir(m.group(4), m.group(5), m.group(6));
                if (desde != null && hasta != null) {
                    borrar(m);
                    return new LocalTime[] {desde, hasta};
                }
            }
            return null;
        }

        boolean contiene(Pattern patron) {
            return patron.matcher(texto).find();
        }

        private boolean negado(int inicio) {
            return NEGACION_PREVIA.matcher(texto.substring(0, inicio)).find();
        }

        private void borrar(Matcher m) {
            for (int i = m.start(); i < m.end(); i++) {
                texto.setCharAt(i, ' ');
            }
        }
    }
}
