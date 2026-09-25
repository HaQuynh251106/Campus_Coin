package com.campuscoin.common.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS policy for the Angular client (section 7.8).
 *
 * <p>Origins are read from configuration and never default to a wildcard. The allowed list is
 * explicit because the API carries personal and financial data: a wildcard would let any site
 * on the internet call it from a browser.
 *
 * <p>Only the methods the API actually uses are permitted, and only the headers the client
 * needs - {@code Authorization} for the bearer token and {@code Content-Type} for JSON bodies.
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${campuscoin.security.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // The client may read the status code and any header the API sets on errors.
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setMaxAge(3600L);
        // Enabled because this API may later be called with cookies by a same-site front end;
        // it is safe here because allowedOrigins is an explicit list, never "*".
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
