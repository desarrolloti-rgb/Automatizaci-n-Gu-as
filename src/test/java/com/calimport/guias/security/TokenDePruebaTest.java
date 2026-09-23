package com.calimport.guias.security;

import org.junit.jupiter.api.Test;

import com.calimport.guias.model.Rol;

/**
 * Utilidad de desarrollo: imprime un JWT válido sin pasar por {@code /api/auth/login}.
 *
 * <p>Sirve para probar los endpoints mientras todavía no hay ningún empleado en SAP con el
 * {@code U_Password} cargado. El token se firma con el mismo secret que usa la app, así que
 * el filtro lo acepta igual que uno emitido por el login.
 *
 * <p>Se corre asi, pasando el secret en el propio comando:
 *
 * <pre>
 * mvnw test -Dtest=TokenDePruebaTest -DfailIfNoTests=false -Djwt.secret=EL-MISMO-SECRET-DE-LA-APP
 * </pre>
 *
 * <p>Tiene que ser <b>el mismo</b> secret con el que levantas la app, o el filtro rechazará
 * el token con 401. Si no se pasa, cae a la variable de entorno {@code JWT_SECRET}.
 *
 * <p><b>Es temporal.</b> Cuando exista un repartidor de verdad en SAP, esto se borra: un
 * test que imprime un token válido en consola no debe llegar nunca a un servidor de CI,
 * donde quedaría en los logs del build.
 */
class TokenDePruebaTest {

    @Test
    void imprimirTokenDePrueba() {
        String secret = System.getProperty("jwt.secret");
        if (secret == null || secret.isBlank()) {
            secret = System.getenv("JWT_SECRET");
        }

        if (secret == null || secret.isBlank()) {
            System.out.println();
            System.out.println("No hay secret. Corre el comando asi:");
            System.out.println("  mvnw test -Dtest=TokenDePruebaTest -DfailIfNoTests=false "
                    + "-Djwt.secret=EL-MISMO-SECRET-DE-LA-APP");
            System.out.println();
            return;
        }

        String token = new JwtTokenProvider(secret, 480)
                .generateToken("prueba@calimport.cl", 1, "Repartidor de Prueba", Rol.Despachador);

        System.out.println();
        System.out.println("=== TOKEN DE PRUEBA (valido 8 horas) ===");
        System.out.println(token);
        System.out.println("========================================");
        System.out.println();
    }
}
