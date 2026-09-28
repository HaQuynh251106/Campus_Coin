package com.campuscoin.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campuscoin.security.password-reset")
public record PasswordResetProperties(String linkBaseUrl,
                                      Boolean sinkEnabled,
                                      String sinkFile,
                                      String fromAddress,
                                      String fromName,
                                      Smtp smtp) {

    public static final String DEFAULT_FROM_NAME = "Campus Coin";

    public record Smtp(String host, Integer port, String username, String password,
                       Boolean starttls, Boolean ssl) {

        public static final int DEFAULT_PORT = 587;

        public boolean isConfigured() {
            return host != null && !host.isBlank();
        }

        public int effectivePort() {
            return port == null || port <= 0 ? DEFAULT_PORT : port;
        }

        public boolean useStartTls() {
            return !Boolean.FALSE.equals(starttls);
        }

        public boolean useSsl() {
            return Boolean.TRUE.equals(ssl);
        }

        public boolean hasCredentials() {
            return username != null && !username.isBlank();
        }
    }

    public boolean hasFromAddress() {
        return fromAddress != null && !fromAddress.isBlank();
    }

    public String effectiveFromName() {
        return fromName == null || fromName.isBlank() ? DEFAULT_FROM_NAME : fromName.trim();
    }
}
