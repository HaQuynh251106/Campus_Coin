package com.campuscoin.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campuscoin.security.login")
public record LoginProperties(Integer defaultMaxAttempts,
                              Integer maxResetRequests,
                              Integer lockoutMinutes) {
}
