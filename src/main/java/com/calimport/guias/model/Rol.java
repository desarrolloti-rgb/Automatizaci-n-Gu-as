package com.calimport.guias.model;

/**
 * Viaja en el claim {@code role} del JWT y el frontend lo lee con estos mismos nombres:
 * si se renombra uno, hay que cambiarlo también en {@code ../Frontend}.
 */
public enum Rol {

    /** Ve y resuelve solo las guías que tiene asignadas. */
    REPARTIDOR,

    /** Sincroniza desde SAP, asigna guías, define horarios y arma rutas. Ve todas las guías. */
    JEFE_BODEGA;

    /**
     * Un claim ausente o desconocido se lee como {@link #REPARTIDOR}, el rol con menos
     * permisos: un token viejo o manipulado nunca termina con más acceso del que tenía.
     */
    public static Rol desdeClaim(Object valor) {
        if (valor instanceof String texto) {
            for (Rol rol : values()) {
                if (rol.name().equals(texto)) {
                    return rol;
                }
            }
        }
        return REPARTIDOR;
    }
}
