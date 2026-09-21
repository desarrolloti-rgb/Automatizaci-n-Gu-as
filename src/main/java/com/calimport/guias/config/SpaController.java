package com.calimport.guias.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Deja que el backend sirva la app Angular con sus propias rutas.
 *
 * <p>El router de Angular usa rutas reales ({@code /bodega}, {@code /ruta}), no un {@code #}.
 * El navegador solo pide la raíz mientras se navega dentro de la app, pero al **recargar** o
 * al abrir un link directo pide {@code GET /bodega}, y para Spring esa ruta no existe: sin
 * esto, recargar en el celular da 404. El reenvío devuelve {@code index.html} y el router
 * resuelve la ruta en el cliente.
 *
 * <p>El patrón toma un solo segmento sin punto: eso deja fuera los archivos del build
 * ({@code main-ABC123.js}), que los sirve el manejador de estáticos, y las rutas de la API,
 * que tienen más de un segmento y llegan antes a su controller. Si algún día la app tuviera
 * rutas anidadas ({@code /bodega/rutas}), hay que ampliarlo.
 */
@Controller
public class SpaController {

    @GetMapping("/{ruta:[^\\.]+}")
    public String reenviarAlIndex() {
        return "forward:/index.html";
    }
}
