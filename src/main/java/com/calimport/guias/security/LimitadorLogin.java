package com.calimport.guias.security;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Freno a la fuerza bruta en el login, con las mismas reglas que el Panel de Facturadores
 * (que vive en la misma VM, bajo otra ruta):
 * <ul>
 *   <li>por correo: tras {@code intentosPorCorreo} fallos seguidos, la cuenta queda bloqueada
 *       {@code bloqueoMinutos};</li>
 *   <li>por IP: {@code intentosPorIp} fallos en {@code ventanaMinutos}, sumando todos los
 *       correos. El bloqueo por correo no frena a quien prueba una clave contra muchos.</li>
 * </ul>
 * nginx ya limita a 5 logins por minuto por IP; esto cubre lo que pase de largo y el acceso
 * directo al puerto. El estado vive en memoria: un reinicio lo limpia, igual que en el panel.
 */
@Component
public class LimitadorLogin {

    private static final int TOPE_REGISTROS = 10_000;

    private final int intentosPorCorreo;
    private final long bloqueoMs;
    private final int intentosPorIp;
    private final long ventanaMs;
    private final Clock reloj;

    private final Map<String, Registro> porCorreo = new ConcurrentHashMap<>();
    private final Map<String, Registro> porIp = new ConcurrentHashMap<>();

    /** Fallos acumulados y desde cuando corre la ventana (o hasta cuando dura el bloqueo). */
    private record Registro(int fallos, long marcaMs) {
    }

    @Autowired
    public LimitadorLogin(
            @Value("${login.intentos-por-correo:5}") int intentosPorCorreo,
            @Value("${login.bloqueo-minutos:5}") long bloqueoMinutos,
            @Value("${login.intentos-por-ip:20}") int intentosPorIp,
            @Value("${login.ventana-minutos:15}") long ventanaMinutos) {
        this(intentosPorCorreo, bloqueoMinutos, intentosPorIp, ventanaMinutos, Clock.systemUTC());
    }

    LimitadorLogin(int intentosPorCorreo, long bloqueoMinutos, int intentosPorIp, long ventanaMinutos, Clock reloj) {
        this.intentosPorCorreo = intentosPorCorreo;
        this.bloqueoMs = bloqueoMinutos * 60_000;
        this.intentosPorIp = intentosPorIp;
        this.ventanaMs = ventanaMinutos * 60_000;
        this.reloj = reloj;
    }

    /** Milisegundos de bloqueo que le quedan a la IP, o 0. Se consulta antes de ir a SAP. */
    public long bloqueoIp(String ip) {
        Registro registro = vigenteIp(ip);
        if (registro == null || registro.fallos() < intentosPorIp) return 0;
        return Math.max(1, registro.marcaMs() + ventanaMs - ahora());
    }

    /** Milisegundos de bloqueo que le quedan al correo, o 0. */
    public long bloqueoCorreo(String correo) {
        Registro registro = porCorreo.get(clave(correo));
        if (registro == null || registro.fallos() < intentosPorCorreo) return 0;
        long restante = registro.marcaMs() - ahora();
        if (restante <= 0) {
            porCorreo.remove(clave(correo));
            return 0;
        }
        return restante;
    }

    public void registrarFallo(String ip, String correo) {
        long ahora = ahora();

        porIp.compute(ip, (k, previo) -> {
            Registro vigente = previo != null && ahora - previo.marcaMs() <= ventanaMs ? previo : null;
            return vigente == null ? new Registro(1, ahora) : new Registro(vigente.fallos() + 1, vigente.marcaMs());
        });

        // Al llegar al tope la marca pasa a ser el fin del bloqueo.
        porCorreo.compute(clave(correo), (k, previo) -> {
            int fallos = previo == null || previo.fallos() >= intentosPorCorreo ? 1 : previo.fallos() + 1;
            return fallos >= intentosPorCorreo ? new Registro(fallos, ahora + bloqueoMs) : new Registro(fallos, ahora);
        });

        // Un barrido desde muchas IPs o con correos inventados no debe hacer crecer los mapas sin fin.
        if (porIp.size() > TOPE_REGISTROS) porIp.keySet().removeIf(ipVieja -> vigenteIp(ipVieja) == null);
        // Se descartan los fallos sueltos, nunca un bloqueo vigente: si no, llenar el mapa
        // con correos inventados serviria para desbloquear una cuenta atacada.
        if (porCorreo.size() > TOPE_REGISTROS) {
            porCorreo.values().removeIf(r -> r.fallos() < intentosPorCorreo || r.marcaMs() <= ahora);
        }
    }

    /** Un login correcto limpia los fallos del correo (no los de la IP: puede ser compartida). */
    public void registrarExito(String correo) {
        porCorreo.remove(clave(correo));
    }

    private Registro vigenteIp(String ip) {
        Registro registro = porIp.get(ip);
        if (registro == null) return null;
        if (ahora() - registro.marcaMs() > ventanaMs) {
            porIp.remove(ip);
            return null;
        }
        return registro;
    }

    private long ahora() {
        return reloj.millis();
    }

    private static String clave(String correo) {
        return correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
    }
}
