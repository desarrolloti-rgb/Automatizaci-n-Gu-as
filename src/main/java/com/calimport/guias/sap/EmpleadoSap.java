package com.calimport.guias.sap;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

import com.calimport.guias.model.Rol;

import tools.jackson.databind.JsonNode;

/**
 * Un empleado de {@code EmployeesInfo} con lo que Guías usa de él: quién es, su correo (con el
 * que inicia sesión), su cargo (del que sale el rol) y si SAP lo tiene activo.
 *
 * @param email sin espacios alrededor; vacío si SAP no lo tiene cargado.
 */
public record EmpleadoSap(int employeeId, String nombre, String email, String cargo, boolean activo) {

    /** Los campos que se piden a SAP para armar uno. */
    static final String CAMPOS = "EmployeeID,FirstName,LastName,eMail,JobTitle,Active";

    /** Null si la fila no trae {@code EmployeeID}: sin él no hay a quién asignarle guías. */
    public static EmpleadoSap de(JsonNode fila) {
        if (fila == null || !fila.hasNonNull("EmployeeID")) {
            return null;
        }
        return new EmpleadoSap(
                fila.get("EmployeeID").asInt(),
                nombreCompleto(fila),
                texto(fila, "eMail"),
                texto(fila, "JobTitle"),
                "tYES".equals(texto(fila, "Active")));
    }

    /** El rol que le da su cargo, o vacío si su cargo no entra a Guías. */
    public Optional<Rol> rol() {
        return rolDeCargo(cargo);
    }

    /**
     * Solo los cargos de bodega entran a Guías. Desde el 2026-11-09 el jefe de bodega entra
     * además al Panel de Facturadores, con una sesión común a las dos
     * ({@link com.calimport.guias.security.SesionComun}); el resto de los cargos sigue entrando a
     * una sola app desde el ingreso común (https://apps.calimport.cl/login, decisión del
     * 2026-10-06). El panel reconoce al jefe con esta misma regla
     * (Panel-Facturadores/backend/src/auth.js): si cambia acá, cambia allá. La comparación
     * ignora mayúsculas, tildes y espacios de más.
     * <ul>
     *   <li>JEFE_BODEGA: "Jefe Bodega" y sus variantes, incluido el typo real "Jefe bogeda".
     *       Desde el 2026-11-25 también "Gerencia Operaciones", que ve todas las pestañas del
     *       portal (panel y Guías) con la misma sesión común.</li>
     *   <li>Despachador: "Bodeguero"/"Bodeguera" con o sin sucursal ("Bodeguero CCP",
     *       "Bodeguera ANT"), "Despachador"/"Despachadora", "Bodega" y "Personal bodega".</li>
     * </ul>
     * Cualquier otro cargo, vacío o ausente no entra: un cargo inesperado no gana acceso
     * por accidente.
     */
    public static Optional<Rol> rolDeCargo(String jobTitle) {
        String cargo = Normalizer.normalize(jobTitle == null ? "" : jobTitle, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");

        if ((cargo.startsWith("jefe") && (cargo.contains("bodega") || cargo.contains("bogeda")))
                || cargo.equals("gerencia operaciones")) {
            return Optional.of(Rol.JEFE_BODEGA);
        }
        if (cargo.startsWith("bodeguer") || cargo.startsWith("despachador") || cargo.equals("bodega")
                || cargo.equals("personal bodega")) {
            return Optional.of(Rol.Despachador);
        }
        return Optional.empty();
    }

    /**
     * OJO: nombres de campo asumidos ("FirstName"/"LastName") por analogía con el resto
     * de EmployeesInfo en Dashboard (JobTitle, WorkBlock, Active). No están confirmados
     * contra el metadata real del Service Layer — verificar antes de confiar en el dato.
     */
    public static String nombreCompleto(JsonNode employee) {
        return (texto(employee, "FirstName") + " " + texto(employee, "LastName")).trim();
    }

    private static String texto(JsonNode fila, String campo) {
        JsonNode valor = fila.get(campo);
        return valor == null || valor.isNull() ? "" : valor.asText("").trim();
    }
}
