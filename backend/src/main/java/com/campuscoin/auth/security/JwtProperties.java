package com.campuscoin.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campuscoin.security.jwt")
public record JwtProperties(String secret, String issuer, Integer defaultTtlMinutes) {
}
