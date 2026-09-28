package com.campuscoin.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.campuscoin.auth.security.LoginProperties;
import com.campuscoin.common.exception.TooManyAttemptsException;
import com.campuscoin.common.setting.SettingReader;

@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private static final String LOGIN_SCOPE = "login:";
    private static final String RESET_SCOPE = "reset:";

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_MAX_RESET_REQUESTS = 3;
    private static final int DEFAULT_LOCKOUT_MINUTES = 15;

    private final SettingReader settingReader;
    private final LoginProperties properties;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(SettingReader settingReader, LoginProperties properties) {
        this.settingReader = settingReader;
        this.properties = properties;
    }

    public void assertLoginAllowed(String email) {
        if (isLocked(LOGIN_SCOPE + normalise(email), maxLoginAttempts())) {
            log.warn("Sign-in throttled: too many failed attempts");
            throw new TooManyAttemptsException(
                    "Too many failed sign-in attempts. Please try again later.");
        }
    }

    public void assertResetAllowed(String email) {
        if (isLocked(RESET_SCOPE + normalise(email), maxResetRequests())) {
            log.warn("Password reset throttled: too many requests");
            throw new TooManyAttemptsException(
                    "Too many password reset requests. Please try again later.");
        }
    }

    public void recordLoginFailure(String email) {
        record(LOGIN_SCOPE + normalise(email));
    }

    public void recordLoginSuccess(String email) {
        attempts.remove(LOGIN_SCOPE + normalise(email));
    }

    public void recordResetRequest(String email) {
        record(RESET_SCOPE + normalise(email));
    }

    private boolean isLocked(String key, int limit) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }

        Instant now = Instant.now();
        Duration window = lockoutDuration();

        if (attempt.isExpired(now, window)) {

            attempts.remove(key, attempt);
            return false;
        }
        return attempt.failures() >= limit;
    }

    private void record(String key) {
        Instant now = Instant.now();
        Duration window = lockoutDuration();
        attempts.compute(key, (ignored, existing) -> {
            if (existing == null || existing.isExpired(now, window)) {
                return new Attempt(1, now);
            }
            return new Attempt(existing.failures() + 1, existing.firstFailureAt());
        });
    }

    private int maxLoginAttempts() {
        int fallback = properties.defaultMaxAttempts() == null
                ? DEFAULT_MAX_ATTEMPTS
                : properties.defaultMaxAttempts();
        return settingReader.getInt(SettingReader.AUTH_MAX_LOGIN_ATTEMPTS, fallback);
    }

    private int maxResetRequests() {
        return properties.maxResetRequests() == null
                ? DEFAULT_MAX_RESET_REQUESTS
                : properties.maxResetRequests();
    }

    private Duration lockoutDuration() {
        int minutes = properties.lockoutMinutes() == null
                ? DEFAULT_LOCKOUT_MINUTES
                : properties.lockoutMinutes();
        return Duration.ofMinutes(minutes);
    }

    private String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private record Attempt(int failures, Instant firstFailureAt) {

        boolean isExpired(Instant now, Duration window) {
            return firstFailureAt.plus(window).isBefore(now);
        }
    }
}
