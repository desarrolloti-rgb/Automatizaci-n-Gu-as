package com.calimport.guias.sap;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.calimport.guias.model.Rol;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmpleadoSapTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode fila() {
        ObjectNode fila = JSON.createObjectNode();
        fila.put("EmployeeID", 31);
        fila.put("FirstName", "Juan");
        fila.put("LastName", "Pérez");
        fila.put("eMail", " juan@calimport.cl ");
        fila.put("JobTitle", "Bodeguero CCP");
        fila.put("Active", "tYES");
        return fila;
    }

    @Test
    void leeLaFilaDeEmployeesInfo() {
        EmpleadoSap empleado = EmpleadoSap.de(fila());

        assertEquals(31, empleado.employeeId());
        assertEquals("Juan Pérez", empleado.nombre());
        assertEquals("juan@calimport.cl", empleado.email());
        assertTrue(empleado.activo());
        assertEquals(Optional.of(Rol.Despachador), empleado.rol());
    }

    @Test
    void activeDistintoDeTYesEsInactivo() {
        ObjectNode fila = fila();
        fila.put("Active", "tNO");
        assertFalse(EmpleadoSap.de(fila).activo());
        fila.remove("Active");
        assertFalse(EmpleadoSap.de(fila).activo());
    }

    @Test
    void sinEmployeeIdNoSirve() {
        ObjectNode fila = fila();
        fila.remove("EmployeeID");
        assertNull(EmpleadoSap.de(fila));
    }

    @Test
    void sinCorreoQuedaVacio() {
        ObjectNode fila = fila();
        fila.putNull("eMail");
        assertEquals("", EmpleadoSap.de(fila).email());
    }

    @Test
    void losCargosRealesDeSapSeReparten() {
        // Cargos tal como estaban en OHEM el 2026-10-06.
        assertEquals(Optional.of(Rol.JEFE_BODEGA), EmpleadoSap.rolDeCargo("Jefe Bodega"));
        assertEquals(Optional.of(Rol.JEFE_BODEGA), EmpleadoSap.rolDeCargo("  jefe   bogeda "));
        assertEquals(Optional.of(Rol.JEFE_BODEGA), EmpleadoSap.rolDeCargo("Gerencia Operaciones"));
        assertEquals(Optional.of(Rol.JEFE_BODEGA), EmpleadoSap.rolDeCargo("GERENCIA OPERACIONES"));
        for (String bodega : List.of("Bodeguero", "Bodeguero CCP", "Bodeguera ANT", "Bodeguero PMO", "Bodega",
                "Personal bodega", "PERSONAL BODEGA", "Despachador", "despachadora")) {
            assertEquals(Optional.of(Rol.Despachador), EmpleadoSap.rolDeCargo(bodega), bodega);
        }
        for (String otro : List.of("Facturador", "Logistica", "STOCK", "Ventas Terreno", "Gerencia Ventas", "Operaciones",
                "Jefe de Ventas", "Soporte TI", "")) {
            assertEquals(Optional.empty(), EmpleadoSap.rolDeCargo(otro), otro);
        }
        assertEquals(Optional.empty(), EmpleadoSap.rolDeCargo(null));
    }
}
