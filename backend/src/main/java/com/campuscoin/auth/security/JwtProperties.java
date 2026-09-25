package com.campuscoin.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code campuscoin.security.jwt} block of {@code application.yml}.
 *
 * @param secret            HS256 signing key, supplied through the {@code JWT_SECRET}
 *                          environment variable. There is no default on purpose: an
 *                          application that starts without a key would sign predictable tokens.
 *                          Must be at least 32 bytes, which is the HS256 minimum.
 * @param issuer            the {@code iss} claim, also required back on every verification.
 * @param defaultTtlMinutes fallback session lifetime, used only when
 *                          {@code auth.session_ttl_minutes} cannot be read from
 *                          {@code system_settings}; that setting is the authoritative value.
 */
@ConfigurationProperties(prefix = "campuscoin.security.jwt")
public record JwtProperties(String secret, String issuer, Integer defaultTtlMinutes) {
}
