package com.calimport.guias.correo;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.web.util.HtmlUtils;

import com.calimport.guias.model.Guia;

/**
 * Asunto y cuerpo del aviso de despacho entregado, en texto y en HTML. Solo arma texto: a
 * quién va y qué se adjunta lo decide {@link CorreoDespachoService}.
 *
 * <p><b>La estética es la de los correos de HES</b> ({@code MCP-HES/src/marca.js} y
 * {@code correo.js}): el cliente recibe de Calimport la solicitud de HES y este aviso, y
 * tienen que verse de la misma empresa. Marco blanco de 600 px sobre gris, logo a color con
 * la etiqueta a la derecha, franja azul marino y naranja, tabla con encabezado marino, firma
 * con la línea naranja y pie marino con el sitio y el lema. Si allá cambia la marca, cambia
 * acá. Tablas, estilos en línea y colores sólidos: Outlook y Gmail ignoran el CSS moderno.
 */
final class MensajeDespacho {

    /** La VM corre en UTC: sin convertir, el cliente leería 19:41 por una entrega de las 16:41. */
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    /** "04-11-2026 16:41", como {@code momentoCL} de HES. */
    private static final DateTimeFormatter MOMENTO = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    /** Content-ID del logo y de la foto, incrustados: Outlook y Gmail bloquean las imágenes remotas. */
    static final String CID_LOGO = "logo@calimport";
    static final String CID_FOTO = "foto-entrega@calimport";

    static final String EMPRESA = "Calimport";
    private static final String ETIQUETA = "Despacho entregado";
    private static final String FIRMA = "Equipo de Despachos\n" + EMPRESA;

    // Colores de la firma corporativa, los mismos de MARCA en marca.js de HES.
    private static final String AZUL = "#0222AE";
    private static final String MARINO = "#0A1633";
    private static final String NARANJA = "#FF7400";
    private static final String GRIS = "#F1F3F4";
    private static final String TEXTO = "#293145";
    private static final String BORDE = "#E3E6EA";
    private static final String PIE_TEXTO = "#C9D1E0";
    private static final String FUENTE = "Arial, Helvetica, sans-serif";
    private static final String FUENTE_TITULO = "'Arial Narrow', 'Roboto Condensed', Arial, Helvetica, sans-serif";

    private static final String ESTILO_TH = "style=\"text-align:left;padding:8px 10px;background:" + MARINO
            + ";color:#ffffff;font-family:" + FUENTE_TITULO
            + ";font-size:12px;font-weight:bold;letter-spacing:1px;text-transform:uppercase\"";
    private static final String ESTILO_TD = "style=\"padding:8px 10px;border-bottom:1px solid " + BORDE
            + ";vertical-align:top\"";
    private static final String ESTILO_TD_FECHA = "style=\"padding:8px 10px;border-bottom:1px solid " + BORDE
            + ";vertical-align:top;white-space:nowrap\"";

    record Contenido(String asunto, String texto, String html) {
    }

    private MensajeDespacho() {
    }

    /**
     * @param conFoto si la foto va en el cuerpo y adjunta (si no se encontró, el aviso sale sin ella)
     * @param respondible si hay casilla de respuesta (Reply-To); si no, se pide no responder
     * @param enProduccion con desvío de prueba, a quién habría ido; null en un envío real
     * @param conLogo si el logo va incrustado; si faltara, la marca va en texto
     */
    static Contenido de(Guia guia, boolean conFoto, boolean respondible, String enProduccion, boolean conLogo) {
        String asunto = (enProduccion != null ? "[PRUEBA] " : "")
                + ETIQUETA + " · Guía " + guia.getFolio() + " · " + EMPRESA;
        return new Contenido(asunto, texto(guia, conFoto, respondible, enProduccion),
                html(guia, conFoto, respondible, enProduccion, conLogo));
    }

    private static String momento(Instant fechaEntrega) {
        return fechaEntrega == null ? "—" : MOMENTO.format(fechaEntrega.atZone(CHILE));
    }

    private static String saludo(Guia guia) {
        return "Estimado equipo de " + guia.getCliente() + ":";
    }

    private static final String INTRO =
            "Junto con saludar, les informamos que el siguiente despacho de " + EMPRESA + " fue entregado.";

    private static String cierre(boolean respondible) {
        return respondible
                ? "Si tienen alguna consulta sobre este despacho, pueden responder a este correo."
                : "Este correo se envía automáticamente; por favor no lo respondan.";
    }

    private static String texto(Guia guia, boolean conFoto, boolean respondible, String enProduccion) {
        StringBuilder t = new StringBuilder();
        if (enProduccion != null) {
            t.append("*** CORREO DE PRUEBA *** En producción se enviaría a: ").append(enProduccion).append("\n\n");
        }
        t.append(saludo(guia)).append("\n\n")
                .append(INTRO).append("\n\n")
                .append("  • Guía ").append(guia.getFolio())
                .append(" | ").append(guia.getDireccion())
                .append(" | Entregado ").append(momento(guia.getFechaEntrega())).append('\n');
        if (conFoto) {
            t.append("\nRespaldo de entrega (foto de la entrega):\n")
                    .append("  • Guía ").append(guia.getFolio()).append(" (adjunta)\n");
        }
        t.append('\n').append(cierre(respondible)).append("\n\n")
                .append("Saludos cordiales,\n")
                .append(FIRMA).append('\n');
        return t.toString();
    }

    private static String html(Guia guia, boolean conFoto, boolean respondible, String enProduccion, boolean conLogo) {
        String folio = String.valueOf(guia.getFolio());
        String aviso = enProduccion == null ? ""
                : "<p style=\"padding:8px 12px;background:#fff4e5;border:1px solid #f5a524;border-radius:6px\">"
                        + "<strong>Correo de prueba.</strong> En producción se enviaría a: "
                        + escapar(enProduccion) + "</p>";

        String tabla = "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-collapse:collapse;font-size:14px;margin:12px 0\">\n"
                + "<thead><tr><th " + ESTILO_TH + ">Guía de despacho</th><th " + ESTILO_TH + ">Dirección</th>"
                + "<th " + ESTILO_TH + ">Fecha de entrega</th></tr></thead>\n"
                + "<tbody><tr><td " + ESTILO_TD + ">" + folio + "</td><td " + ESTILO_TD + ">"
                + escapar(guia.getDireccion()) + "</td><td " + ESTILO_TD_FECHA + ">"
                + momento(guia.getFechaEntrega()) + "</td></tr></tbody></table>\n";

        // Como el respaldo de HES: la foto adentro del cuerpo, para verla sin abrir el adjunto.
        String respaldo = !conFoto ? ""
                : "<p style=\"margin:16px 0 4px\"><strong>Respaldo de entrega</strong> (foto de la entrega):</p>\n"
                        + "<ul style=\"margin:0 0 12px;padding-left:20px\">\n"
                        + "<li style=\"margin-bottom:8px\">Guía " + folio + " (adjunta)<br>"
                        + "<img src=\"cid:" + CID_FOTO + "\" alt=\"Foto de la entrega de la guía " + folio
                        + "\" width=\"480\" style=\"display:block;width:100%;max-width:480px;height:auto;"
                        + "margin-top:6px;border:1px solid #d0d5dd;border-radius:4px\"></li>\n</ul>\n";

        String cuerpo = "<p style=\"margin:0 0 12px\">" + escapar(saludo(guia)) + "</p>\n"
                + "<p style=\"margin:0 0 12px\">" + escapar(INTRO) + "</p>\n"
                + tabla
                + respaldo
                + "<p style=\"margin:12px 0\">" + escapar(cierre(respondible)) + "</p>\n"
                + "<p style=\"margin:16px 0 8px\">Saludos cordiales,</p>\n"
                + firma();

        String preheader = "La guía de despacho " + folio + " fue entregada.";
        return envolver(cuerpo, aviso, preheader, conLogo);
    }

    /** {@code firmaHtml} de HES: la línea naranja a la izquierda, en una celda (Outlook respeta bordes de celda). */
    private static String firma() {
        String[] lineas = FIRMA.split("\n");
        StringBuilder resto = new StringBuilder();
        for (int i = 1; i < lineas.length; i++) {
            resto.append("<br>").append(escapar(lineas[i]));
        }
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin-top:4px\">"
                + "<tr><td style=\"border-left:3px solid " + NARANJA + ";padding:2px 0 2px 12px;font-family:" + FUENTE
                + ";font-size:14px;line-height:1.5;color:" + TEXTO + "\"><strong style=\"color:" + MARINO + "\">"
                + escapar(lineas[0]) + "</strong>" + resto + "</td></tr></table>";
    }

    /** {@code envolverCorreo} de HES: el marco de marca alrededor del cuerpo. */
    private static String envolver(String cuerpo, String aviso, String preheader, boolean conLogo) {
        String marca = conLogo
                ? "<img src=\"cid:" + CID_LOGO + "\" width=\"160\" height=\"49\" alt=\"calimport\" "
                        + "style=\"display:block;border:0;outline:none;width:160px;height:auto\">"
                : "<span style=\"font-family:" + FUENTE + ";font-size:26px;font-weight:bold;color:" + AZUL
                        + ";letter-spacing:-0.5px\"><span style=\"color:" + NARANJA + "\">&#9670;</span> calimport</span>";
        String franja = celda(MARINO, "72%") + celda(NARANJA, "28%");
        return "<!doctype html>\n<html lang=\"es\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + ETIQUETA + "</title>\n</head>\n<body style=\"margin:0;padding:0;background:" + GRIS + "\">\n"
                + "<div style=\"display:none;max-height:0;max-width:0;overflow:hidden;mso-hide:all;font-size:1px;"
                + "line-height:1px;color:" + GRIS + ";opacity:0\">" + escapar(preheader) + "</div>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\""
                + GRIS + "\" style=\"background:" + GRIS + "\">\n"
                + "<tr><td align=\"center\" style=\"padding:24px 12px\">\n"
                + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"width:100%;max-width:600px;background:#ffffff;border:1px solid " + BORDE + "\">\n"
                + "<tr><td style=\"padding:20px 28px 16px\">\n"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>\n"
                + "<td valign=\"middle\">" + marca + "</td>\n"
                + "<td valign=\"middle\" align=\"right\" style=\"font-family:" + FUENTE_TITULO + ";font-size:12px;"
                + "font-weight:bold;letter-spacing:2px;text-transform:uppercase;color:" + MARINO + "\">" + ETIQUETA + "</td>\n"
                + "</tr></table>\n</td></tr>\n"
                + "<tr><td style=\"padding:0\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" "
                + "cellspacing=\"0\" border=\"0\"><tr>" + franja + "</tr></table></td></tr>\n"
                + "<tr><td style=\"padding:24px 28px 12px;font-family:" + FUENTE + ";font-size:14px;line-height:1.55;color:"
                + TEXTO + "\">" + aviso + cuerpo + "</td></tr>\n"
                + "<tr><td bgcolor=\"" + MARINO + "\" style=\"background:" + MARINO + ";padding:16px 28px\">\n"
                + "<p style=\"margin:0;font-family:" + FUENTE_TITULO + ";font-size:12px;font-weight:bold;letter-spacing:2px;"
                + "color:#ffffff\">WWW.CALIMPORT.CL</p>\n"
                + "<p style=\"margin:6px 0 0;font-family:" + FUENTE_TITULO + ";font-size:11px;letter-spacing:1px;"
                + "text-transform:uppercase;color:" + PIE_TEXTO + "\">Tecnología que impulsa el <span style=\"color:"
                + NARANJA + "\">presente</span>. Soluciones que construyen el <span style=\"color:" + NARANJA
                + "\">futuro</span>.</p>\n"
                + "</td></tr>\n</table>\n</td></tr></table>\n</body>\n</html>\n";
    }

    private static String celda(String color, String ancho) {
        return "<td width=\"" + ancho + "\" height=\"5\" bgcolor=\"" + color + "\" style=\"background:" + color
                + ";height:5px;font-size:0;line-height:0\">&nbsp;</td>";
    }

    /** Solo los caracteres especiales de HTML: el correo va en UTF-8 y las tildes viajan tal cual. */
    private static String escapar(String texto) {
        return texto == null ? "" : HtmlUtils.htmlEscape(texto, StandardCharsets.UTF_8.name());
    }
}
