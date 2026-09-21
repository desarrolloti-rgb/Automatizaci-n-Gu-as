package com.calimport.guias.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.calimport.guias.config.LocalAuthConfig;
import com.calimport.guias.config.LocalAuthConfig.Usuario;
import com.calimport.guias.controller.dto.LoginRequest;
import com.calimport.guias.controller.dto.LoginResponse;
import com.calimport.guias.security.JwtTokenProvider;
import com.calimport.guias.service.RepartidorService;
import com.calimport.guias.utils.ApiException;

import jakarta.validation.Valid;

/**
 * Reemplaza a {@link AuthController} cuando {@code auth.modo=local}: mismo endpoint y mismo
 * token, pero valida contra los usuarios de configuración en vez de SAP. La app que consume
 * la API no nota la diferencia.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "auth.modo", havingValue = "local")
public class LocalAuthController {

    private final LocalAuthConfig config;
    private final RepartidorService repartidorService;
    private final JwtTokenProvider jwtTokenProvider;

    public LocalAuthController(LocalAuthConfig config, RepartidorService repartidorService,
                               JwtTokenProvider jwtTokenProvider) {
        this.config = config;
        this.repartidorService = repartidorService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody @Valid LoginRequest request) {
        Usuario usuario = config.getUsuarios().stream()
                .filter(u -> u.getEmail().equalsIgnoreCase(request.email().trim()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No existe un usuario con ese email"));

        // Comparación en tiempo constante, por costumbre aunque sea local.
        if (!MessageDigest.isEqual(usuario.getPassword().getBytes(StandardCharsets.UTF_8),
                request.password().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Contraseña incorrecta");
        }

        // Igual que con SAP: el login mantiene al día la copia local del repartidor.
        repartidorService.upsertDesdeSap(usuario.getEmployeeId(), usuario.getNombre(), usuario.getEmail(), true,
                usuario.getRol());
        return new LoginResponse(jwtTokenProvider.generateToken(usuario.getEmail(), usuario.getEmployeeId(),
                usuario.getNombre(), usuario.getRol()));
    }
}
