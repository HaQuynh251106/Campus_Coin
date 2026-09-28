package com.campuscoin.auth.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.campuscoin.auth.entity.User;
import com.campuscoin.auth.entity.UserRole;
import com.campuscoin.common.exception.UnauthenticatedException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtService {

    private final SecretKey signingKey;
    private final String issuer;

    public JwtService(JwtProperties properties) {
        byte[] keyBytes = properties.secret().getBytes(StandardCharsets.UTF_8);

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.issuer = properties.issuer();
    }

    public IssuedToken issue(User user, int ttlMinutes, Instant now) {
        Instant expiresAt = now.plus(Duration.ofMinutes(ttlMinutes));
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("tv", user.getTokenVersion())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    public JwtClaims verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new JwtClaims(
                    Long.valueOf(claims.getSubject()),
                    claims.get("email", String.class),
                    UserRole.valueOf(claims.get("role", String.class)),
                    claims.get("tv", Integer.class));
        } catch (JwtException | IllegalArgumentException e) {

            throw new UnauthenticatedException("The access token is invalid or has expired.");
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public record JwtClaims(Long userId, String email, UserRole role, Integer tokenVersion) {
    }
}
