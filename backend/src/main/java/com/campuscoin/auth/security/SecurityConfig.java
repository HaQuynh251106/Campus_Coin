package com.campuscoin.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.campuscoin.auth.service.SessionService;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_POST_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/password-reset/verify",
            "/api/v1/auth/password-reset/complete",
            "/api/v1/admin/auth/login"
    };

    private static final String[] PUBLIC_READ_ENDPOINTS = {
            "/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/info"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   SessionService sessionService,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler,
                                                   CorsConfigurationSource corsConfigurationSource)
            throws Exception {

        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(jwtService, sessionService, authenticationEntryPoint);

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_READ_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                        .requestMatchers("/api/v1/profile/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/categories/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/transactions/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/recurring-rules/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/budgets/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/notifications/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/dashboard/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/reports/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/tips/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/bookmarks/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/recent-activity/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/anomalies/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/forecast/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/ai/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/insights/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/imports/**").hasRole("STUDENT")

                        .requestMatchers("/api/v1/chat/**").hasRole("STUDENT")
                        .requestMatchers("/api/**").authenticated()

                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
