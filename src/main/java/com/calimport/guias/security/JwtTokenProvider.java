package com.calimport.guias.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long ttlMinutes;

    public JwtTokenProvider(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.access-token-ttl-minutes}") long ttlMinutes) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException(
                    "JWT secret debe tener al menos 32 caracteres. Actual: " + (secret == null ? 0 : secret.length()));
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
    }

    public String generateToken(String email, int employeeId, String nombre) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlMinutes * 60_000);

        return Jwts.builder()
                .subject(email)
                .claim("role", "REPARTIDOR")
                .claim("employeeId", employeeId)
                .claim("nombre", nombre)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
