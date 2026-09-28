package com.campuscoin.auth.security;

import org.springframework.stereotype.Component;

@Component
public class PasswordResetLinkBuilder {

    private final PasswordResetProperties properties;

    public PasswordResetLinkBuilder(PasswordResetProperties properties) {
        this.properties = properties;
    }

    public String build(String rawToken) {
        String baseUrl = properties.linkBaseUrl();
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "token=" + rawToken;
    }
}
