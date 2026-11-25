package com.calimport.guias.correo;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El campo EmailAddress de la ficha del cliente, tal como lo escriben en SAP. */
class DireccionesCorreoTest {

    @Test
    void unSoloCorreo() {
        assertEquals(List.of("compras@cliente.cl"), DireccionesCorreo.extraer("compras@cliente.cl"));
    }

    @Test
    void variosCorreosSeparadosComoVengan() {
        // En la ficha caben varios en un campo de 100: punto y coma, coma o espacios.
        assertEquals(List.of("a@cliente.cl", "b@cliente.cl", "c@cliente.cl", "d@cliente.cl"),
                DireccionesCorreo.extraer("a@cliente.cl; b@cliente.cl,c@cliente.cl  d@cliente.cl"));
    }

    @Test
    void enMinusculasYSinRepetir() {
        assertEquals(List.of("compras@cliente.cl"),
                DireccionesCorreo.extraer("Compras@Cliente.cl;compras@cliente.cl"));
    }

    @Test
    void descartaLoQueNoEsUnCorreoYConservaElResto() {
        // Un correo mal escrito no puede dejar sin aviso a los que sí están bien.
        assertEquals(List.of("ok@cliente.cl"),
                DireccionesCorreo.extraer("sin-arroba.cl; ok@cliente.cl; @cliente.cl; nombre@sindominio"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "no tiene", "-"})
    void sinCorreosDevuelveVacio(String campo) {
        assertTrue(DireccionesCorreo.extraer(campo).isEmpty());
    }

    @Test
    void unSaltoDeLineaNoPuedeColarseEnUnaDireccion() {
        // Un salto en una dirección permitiría agregar encabezados al correo.
        assertFalse(DireccionesCorreo.esValida("a@cliente.cl\r\nBcc: otro@x.cl"));
        assertEquals(List.of("a@cliente.cl", "otro@x.cl"),
                DireccionesCorreo.extraer("a@cliente.cl\r\nBcc: otro@x.cl"));
    }

    @Test
    void laListaDeConfiguracionSeLeeIgual() {
        assertEquals(List.of("logistica@calimport.cl", "ventas@calimport.cl"),
                DireccionesCorreo.extraer("logistica@calimport.cl, ventas@calimport.cl"));
    }
}
