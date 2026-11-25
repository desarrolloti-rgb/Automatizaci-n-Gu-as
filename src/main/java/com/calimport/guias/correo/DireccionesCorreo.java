package com.calimport.guias.correo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lee las casillas de un texto libre: el {@code EmailAddress} de la ficha del cliente en SAP,
 * donde caben varias, o una lista de la configuración.
 */
final class DireccionesCorreo {

    /**
     * Conservadora a propósito: sin espacios, saltos ni los caracteres que arman una lista de
     * encabezados ({@code <>,;"}). Un salto de línea en una dirección permitiría agregarle
     * encabezados al correo. Exige un punto en el dominio: "nombre@sindominio" es un error de
     * tipeo, no una casilla.
     */
    private static final Pattern VALIDA =
            Pattern.compile("^[^\\s@<>,;\"()\\[\\]]+@[^\\s@<>,;\"()\\[\\]]+\\.[^\\s@<>,;\"()\\[\\].]+$");

    private DireccionesCorreo() {
    }

    static boolean esValida(String direccion) {
        return direccion != null && VALIDA.matcher(direccion).matches();
    }

    /**
     * Las casillas válidas, en minúsculas, sin repetir y en el orden en que vienen. Lo que no
     * es un correo se descarta: uno mal escrito no puede dejar sin aviso a los que sí están bien.
     */
    static List<String> extraer(String texto) {
        List<String> casillas = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return casillas;
        }
        for (String parte : texto.split("[;,\\s]+")) {
            String casilla = parte.trim().toLowerCase(Locale.ROOT);
            if (esValida(casilla) && !casillas.contains(casilla)) {
                casillas.add(casilla);
            }
        }
        return casillas;
    }
}
