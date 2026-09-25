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

/**
 * Issues and verifies the access tokens of UC-02.
 *
 * <p>HS256 with a symmetric key taken from configuration. The key is never logged and never
 * leaves this class; the token itself is returned to the caller exactly once, in the sign-in
 * response, and is stored client-side. The server keeps only its SHA-256 hash.
 *
 * <p>Claims carried:
 * <ul>
 *   <li>{@code sub} - the account id. This is the only user identity the API trusts.</li>
 *   <li>{@code email}, {@code role} - convenience values for the filter and the response.</li>
 *   <li>{@code tv} - the account's {@code token_version} at issue time. BR-03 uses it to kill
 *       tokens that predate a password reset or an account disable: the filter compares this
 *       claim with the current column and rejects a mismatch, so revocation is immediate even
 *       though the token's own expiry has not passed.</li>
 *   <li>{@code jti} - a random id. Tokens are hashed into {@code uk_sessions_token}, and two
 *       sign-ins in the same second would otherwise produce a byte-identical token and collide;
 *       the unique id keeps every session row distinct.</li>
 * </ul>
 *
 * <p>Verification failures all raise the same {@link UnauthenticatedException} - an expired,
 * tampered, malformed or wrongly signed token must not be distinguishable by the caller.
 */
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final String issuer;

    public JwtService(JwtProperties properties) {
        byte[] keyBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        // Throws WeakKeyException at start-up if the configured secret is under 256 bits.
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.issuer = properties.issuer();
    }

    /**
     * Mints an access token for a freshly authenticated account.
     *
     * @param user          the authenticated account, already checked for status and role
     * @param ttlMinutes    session lifetime, read from {@code auth.session_ttl_minutes}
     * @param now           the issuing instant, so the session row and the token agree
     */
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

    /**
     * Verifies signature and expiry and returns the claims.
     *
     * @throws UnauthenticatedException if the token is unparsable, mis-signed or expired
     */
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
            // Deliberately not logged with the token: the reason is not needed and the value
            // must never reach a log file.
            throw new UnauthenticatedException("The access token is invalid or has expired.");
        }
    }

    /** An access token and the moment it stops being accepted. */
    public record IssuedToken(String token, Instant expiresAt) {
    }

    /** The claims this API reads back out of a verified token. */
    public record JwtClaims(Long userId, String email, UserRole role, Integer tokenVersion) {
    }
}
