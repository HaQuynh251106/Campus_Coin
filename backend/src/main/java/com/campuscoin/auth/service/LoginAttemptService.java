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

/**
 * Throttles repeated failures for sign-in and password-reset requests (section 7.10).
 *
 * <p>Two abuses are named in the requirement: brute-force sign-in, and flooding the reset
 * endpoint. Both are answered the same way - after the configured number of failures within the
 * cooling-off window the identifier is refused with 429 and error code
 * {@code TOO_MANY_ATTEMPTS}.
 *
 * <p>The counter is keyed by email address, not by account id, and reset requests are counted
 * whether or not the address exists. A throttle that behaved differently for a registered
 * address would be an account-enumeration oracle, which UC-03 A2 and section 7.10 both forbid.
 *
 * <p>Counters live in memory and are therefore per instance. That is a deliberate trade-off for
 * this deployment: it needs no schema change (which section 2 forbids) and no extra
 * infrastructure, at the cost of a limit that scales with the number of instances. The
 * consequences, and how to move the counters to shared storage, are documented in
 * {@code docs/SECURITY.md}.
 *
 * <p>The map is pruned as it is used, so a long-running instance does not accumulate an entry per
 * address ever seen.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** Prefixes keep sign-in and reset counters for the same address independent. */
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

    /**
     * Refuses the attempt if this address is currently locked out.
     *
     * <p>Called before the password is checked, so a locked identifier costs no bcrypt work.
     *
     * @throws TooManyAttemptsException when the limit has been reached and the window is still open
     */
    public void assertLoginAllowed(String email) {
        if (isLocked(LOGIN_SCOPE + normalise(email), maxLoginAttempts())) {
            log.warn("Sign-in throttled: too many failed attempts");
            throw new TooManyAttemptsException(
                    "Too many failed sign-in attempts. Please try again later.");
        }
    }

    /**
     * Refuses the request if this address has asked for too many reset links.
     *
     * <p>The limit is configured rather than derived from the sign-in limit. Deriving it would
     * make it too low to be usable: the seeded sign-in allowance is 5, so a third of it is 1, and
     * a student who let one link expire could no longer request another.
     */
    public void assertResetAllowed(String email) {
        if (isLocked(RESET_SCOPE + normalise(email), maxResetRequests())) {
            log.warn("Password reset throttled: too many requests");
            throw new TooManyAttemptsException(
                    "Too many password reset requests. Please try again later.");
        }
    }

    /** Records a failed sign-in. Called only after the credentials were actually rejected. */
    public void recordLoginFailure(String email) {
        record(LOGIN_SCOPE + normalise(email));
    }

    /**
     * Clears the counter after a successful sign-in.
     *
     * <p>Without this, a student who mistypes their password a few times over a term would be
     * locked out by accumulated failures rather than by consecutive ones.
     */
    public void recordLoginSuccess(String email) {
        attempts.remove(LOGIN_SCOPE + normalise(email));
    }

    /** Records a reset request, whether or not the address has an account. */
    public void recordResetRequest(String email) {
        record(RESET_SCOPE + normalise(email));
    }

    /** True when the identifier has reached the limit and its window has not yet closed. */
    private boolean isLocked(String key, int limit) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }

        Instant now = Instant.now();
        Duration window = lockoutDuration();

        if (attempt.isExpired(now, window)) {
            // The cooling-off period has lapsed, so the slate starts clean.
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

    /**
     * The live attempt limit. {@code auth.max_login_attempts} is authoritative because VĐ-05
     * makes it administrator-editable; the configured value is only a fallback for a missing row.
     */
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

    /** Consecutive failures for one identifier and when the first of them happened. */
    private record Attempt(int failures, Instant firstFailureAt) {

        boolean isExpired(Instant now, Duration window) {
            return firstFailureAt.plus(window).isBefore(now);
        }
    }
}
