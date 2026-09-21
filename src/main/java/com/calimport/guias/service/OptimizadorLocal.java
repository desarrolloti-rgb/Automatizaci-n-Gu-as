package com.calimport.guias.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.calimport.guias.config.RutasConfig;
import com.calimport.guias.model.Guia;

/**
 * Optimizador gratuito, calculado en el backend. Es el que se usa por defecto
 * ({@code rutas.optimizador=local}).
 *
 * <p><b>Tiempos:</b> sin un servicio de mapas no hay calles ni tráfico, así que el viaje se
 * estima con la distancia en línea recta, corregida por {@link #FACTOR_CALLES}, a
 * {@code rutas.velocidad-promedio-kmh}. Las horas de llegada son una estimación; el orden,
 * que es lo que importa, sale bien porque depende de las distancias relativas.
 *
 * <p><b>Costo:</b> el mismo criterio que con Google ({@link OptimizadorGoogle}). Cada hora de
 * ruta cuesta 60, cada kilómetro 1 y cada hora de atraso 1000: llegar fuera de horario se
 * evita salvo que no haya alternativa, pero la guía nunca queda fuera. Si se llega antes de
 * que abran, se espera (y esa espera cuenta como tiempo de ruta).
 *
 * <p><b>Búsqueda:</b> se arman dos recorridos iniciales (el vecino más conveniente en cada
 * paso, y por hora de cierre) y cada uno se mejora moviendo una parada a otra posición o
 * invirtiendo un tramo, mientras baje el costo. Con 50 guías tarda milisegundos.
 */
@Service
@ConditionalOnProperty(prefix = "rutas", name = "optimizador", havingValue = "local", matchIfMissing = true)
public class OptimizadorLocal implements OptimizadorRutas {

    /** En ciudad el recorrido por calles ronda 1,3-1,4 veces la distancia en línea recta. */
    static final double FACTOR_CALLES = 1.35;

    private static final double RADIO_TIERRA_KM = 6371.0;
    private static final double EPSILON = 1e-9;
    private static final int MAX_PASADAS = 200;

    private final RutasConfig config;

    public OptimizadorLocal(RutasConfig config) {
        this.config = config;
    }

    @Override
    public List<Visita> optimizar(LocalDate fecha, LocalTime horaSalida, List<Guia> guias) {
        if (guias.isEmpty()) {
            return List.of();
        }
        Problema problema = new Problema(guias, horaSalida);

        Recorrido mejor = null;
        for (int[] inicial : List.of(problema.vecinoMasConveniente(), problema.porHoraDeCierre())) {
            Recorrido r = problema.mejorar(inicial);
            if (mejor == null || r.costo() < mejor.costo() - EPSILON) {
                mejor = r;
            }
        }

        ZonedDateTime medianoche = fecha.atStartOfDay(config.zona());
        List<Visita> visitas = new ArrayList<>();
        for (int i = 0; i < mejor.orden().length; i++) {
            Guia guia = guias.get(mejor.orden()[i]);
            long segundos = Math.round(mejor.inicios()[i] * 60);
            visitas.add(new Visita(guia.getId(), medianoche.plusSeconds(segundos).toInstant()));
        }
        return visitas;
    }

    /** Distancia sobre la superficie de la Tierra, en kilómetros. */
    static double kilometros(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * RADIO_TIERRA_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * @param orden índices de las guías en el orden de visita
     * @param inicios minuto del día en que empieza cada visita (después de esperar si llegó temprano)
     */
    record Recorrido(int[] orden, double[] inicios, double costo) {
    }

    /** Las guías llevadas a números: minutos del día y una matriz de distancias. */
    private final class Problema {

        private final int n;
        /** Índice 0 = bodega; la guía i es el punto i + 1. */
        private final double[][] km;
        private final double[][] minutos;
        private final Double[] abre;
        private final Double[] cierra;
        private final double salida;
        private final double minutosPorParada;

        Problema(List<Guia> guias, LocalTime horaSalida) {
            if (config.getVelocidadPromedioKmh() <= 0) {
                throw new IllegalStateException("rutas.velocidad-promedio-kmh debe ser mayor que 0");
            }
            n = guias.size();
            double[] lat = new double[n + 1];
            double[] lng = new double[n + 1];
            lat[0] = config.getOrigenLatitud();
            lng[0] = config.getOrigenLongitud();
            abre = new Double[n];
            cierra = new Double[n];
            for (int i = 0; i < n; i++) {
                Guia g = guias.get(i);
                if (g.getLatitud() == null || g.getLongitud() == null) {
                    throw new IllegalStateException("La guía " + g.getId() + " no tiene coordenadas");
                }
                lat[i + 1] = g.getLatitud();
                lng[i + 1] = g.getLongitud();
                abre[i] = minutoDelDia(g.getVentanaDesde());
                cierra[i] = minutoDelDia(g.getVentanaHasta());
            }

            km = new double[n + 1][n + 1];
            minutos = new double[n + 1][n + 1];
            double minutosPorKm = 60.0 / config.getVelocidadPromedioKmh();
            for (int a = 0; a <= n; a++) {
                for (int b = 0; b <= n; b++) {
                    km[a][b] = kilometros(lat[a], lng[a], lat[b], lng[b]) * FACTOR_CALLES;
                    minutos[a][b] = km[a][b] * minutosPorKm;
                }
            }
            salida = horaSalida.toSecondOfDay() / 60.0;
            minutosPorParada = config.getMinutosPorParada();
        }

        private static Double minutoDelDia(LocalTime hora) {
            return hora == null ? null : hora.toSecondOfDay() / 60.0;
        }

        /** Simula el recorrido: a qué hora se llega a cada parada y cuánto cuesta. */
        Recorrido evaluar(int[] orden) {
            double[] inicios = new double[orden.length];
            double t = salida;
            double kilometros = 0;
            double minutosDeAtraso = 0;
            int anterior = 0;
            for (int i = 0; i < orden.length; i++) {
                int punto = orden[i] + 1;
                t += minutos[anterior][punto];
                kilometros += km[anterior][punto];
                Double apertura = abre[orden[i]];
                if (apertura != null && t < apertura) {
                    t = apertura;
                }
                inicios[i] = t;
                Double cierre = cierra[orden[i]];
                if (cierre != null && t > cierre) {
                    minutosDeAtraso += t - cierre;
                }
                t += minutosPorParada;
                anterior = punto;
            }
            // Sin vuelta a la bodega: la ruta termina en el último cliente, igual que con Google.
            double costo = (t - salida) / 60 * OptimizadorGoogle.COSTO_HORA_RUTA
                    + kilometros * OptimizadorGoogle.COSTO_KILOMETRO
                    + minutosDeAtraso / 60 * OptimizadorGoogle.COSTO_HORA_FUERA_DE_HORARIO;
            return new Recorrido(orden, inicios, costo);
        }

        /** En cada paso va a la parada que menos suma al costo del recorrido hasta ahí. */
        int[] vecinoMasConveniente() {
            int[] orden = new int[n];
            boolean[] usada = new boolean[n];
            for (int paso = 0; paso < n; paso++) {
                int elegida = -1;
                double menorCosto = Double.MAX_VALUE;
                for (int candidata = 0; candidata < n; candidata++) {
                    if (usada[candidata]) {
                        continue;
                    }
                    orden[paso] = candidata;
                    double costo = evaluar(Arrays.copyOf(orden, paso + 1)).costo();
                    if (costo < menorCosto - EPSILON) {
                        menorCosto = costo;
                        elegida = candidata;
                    }
                }
                orden[paso] = elegida;
                usada[elegida] = true;
            }
            return orden;
        }

        /** Primero las que cierran antes; las sin horario al final, las más cercanas primero. */
        int[] porHoraDeCierre() {
            return IntStream.range(0, n).boxed()
                    .sorted(Comparator.<Integer>comparingDouble(i -> cierra[i] == null ? Double.MAX_VALUE : cierra[i])
                            .thenComparingDouble(i -> km[0][i + 1]))
                    .mapToInt(Integer::intValue)
                    .toArray();
        }

        /** Búsqueda local: mueve una parada o invierte un tramo mientras el costo baje. */
        Recorrido mejorar(int[] inicial) {
            Recorrido actual = evaluar(inicial);
            for (int pasada = 0; pasada < MAX_PASADAS; pasada++) {
                Recorrido mejorado = mejorarUnaVez(actual);
                if (mejorado == null) {
                    break;
                }
                actual = mejorado;
            }
            return actual;
        }

        private Recorrido mejorarUnaVez(Recorrido actual) {
            int[] orden = actual.orden();
            for (int desde = 0; desde < n; desde++) {
                for (int hacia = 0; hacia < n; hacia++) {
                    if (desde == hacia) {
                        continue;
                    }
                    Recorrido r = evaluar(mover(orden, desde, hacia));
                    if (r.costo() < actual.costo() - EPSILON) {
                        return r;
                    }
                }
            }
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    Recorrido r = evaluar(invertir(orden, i, j));
                    if (r.costo() < actual.costo() - EPSILON) {
                        return r;
                    }
                }
            }
            return null;
        }

        /** Saca la parada de la posición {@code desde} y la deja en {@code hacia}. */
        private static int[] mover(int[] orden, int desde, int hacia) {
            int[] copia = orden.clone();
            int movida = copia[desde];
            if (desde < hacia) {
                System.arraycopy(copia, desde + 1, copia, desde, hacia - desde);
            } else {
                System.arraycopy(copia, hacia, copia, hacia + 1, desde - hacia);
            }
            copia[hacia] = movida;
            return copia;
        }

        private static int[] invertir(int[] orden, int i, int j) {
            int[] copia = orden.clone();
            for (int a = i, b = j; a < b; a++, b--) {
                int tmp = copia[a];
                copia[a] = copia[b];
                copia[b] = tmp;
            }
            return copia;
        }
    }
}
