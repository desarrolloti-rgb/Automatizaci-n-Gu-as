package com.calimport.guias.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import com.calimport.guias.model.Rol;
import com.calimport.guias.utils.ApiException;

import io.jsonwebtoken.Claims;

/**
 * Quién hace la request, leído del JWT que validó {@link JwtAuthenticationFilter}. Es la
 * única fuente para decidir permisos: nada de lo que mande el cliente en la request.
 */
public record UsuarioActual(int employeeId, Rol rol) {

    public static UsuarioActual de(Authentication authentication) {
        if (authentication == null
                || !(authentication.getDetails() instanceof Claims claims)
                || claims.get("employeeId") == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "El token no identifica a un usuario de la app");
        }
        return new UsuarioActual(claims.get("employeeId", Integer.class), Rol.desdeClaim(claims.get("role")));
    }

    public boolean esJefeBodega() {
        return rol == Rol.JEFE_BODEGA;
    }
}
