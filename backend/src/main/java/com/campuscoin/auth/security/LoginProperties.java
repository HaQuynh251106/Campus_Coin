package com.campuscoin.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code campuscoin.security.login} block of {@code application.yml}.
 *
 * @param defaultMaxAttempts fallback for {@code auth.max_login_attempts}. The setting in
 *                           {@code system_settings} is authoritative and administrator-editable
 *                           (VĐ-05); this value is used only when that row is missing or unusable.
 * @param maxResetRequests   how many reset links one address may request per cooling-off window.
 *                           Kept separate from the sign-in limit on purpose: a student who mistypes
 *                           a password five times is ordinary, and the reset limit has to stay high
 *                           enough that requesting a replacement link after one expires is still
 *                           possible. There is no {@code system_settings} key for it, and section 2
 *                           forbids adding one, so it is configured here.
 * @param lockoutMinutes     the cooling-off window. Once it lapses, the counter for that address
 *                           starts again.
 *
 * <p>Login and reset counters live in memory per instance - see {@code docs/SECURITY.md} for the
 * consequences and how to replace them with shared storage.
 */
@ConfigurationProperties(prefix = "campuscoin.security.login")
public record LoginProperties(Integer defaultMaxAttempts,
                              Integer maxResetRequests,
                              Integer lockoutMinutes) {
}
