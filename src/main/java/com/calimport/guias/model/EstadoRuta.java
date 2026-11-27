package com.calimport.guias.model;

/**
 * En qué va una ruta. Se calcula al leerla y no se guarda, igual que P y T de SAP salen de
 * {@code recibidaPorRepartidor}: una segunda copia del mismo hecho se desincroniza. Sale de la
 * fecha, del odómetro y del resultado de cada parada.
 */
public enum EstadoRuta {

    /** Todavía no sale: sin odómetro de salida y sin ninguna parada resuelta. */
    PLANIFICADA,
    /** Salió (anotó el odómetro o resolvió alguna parada) y le falta algo para terminar. */
    EN_CURSO,
    /** Todas las paradas resueltas y, si vuelve a bodega, el odómetro de término anotado. */
    COMPLETADA,
    /** Un día que ya pasó y quedó sin completar. */
    CON_PENDIENTES;

    /**
     * @param paradas cuántas paradas tiene (una guía que salió dos veces cuenta dos)
     * @param resueltas cuántas de ellas se entregaron o rechazaron
     * @param diaPasado si la fecha de la ruta es anterior a hoy, en la zona de las rutas
     */
    public static EstadoRuta de(int paradas, int resueltas, boolean vuelveABodega,
                                boolean conOdometroSalida, boolean conOdometroTermino, boolean diaPasado) {
        // Sin paradas (bodega las sacó todas) no hay nada que completar ni que quede pendiente.
        boolean completada = paradas > 0 && resueltas >= paradas && (!vuelveABodega || conOdometroTermino);
        if (completada) {
            return COMPLETADA;
        }
        if (diaPasado && paradas > 0) {
            return CON_PENDIENTES;
        }
        return conOdometroSalida || resueltas > 0 ? EN_CURSO : PLANIFICADA;
    }
}
