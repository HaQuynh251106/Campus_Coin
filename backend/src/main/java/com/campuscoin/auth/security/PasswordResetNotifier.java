package com.campuscoin.auth.security;

/**
 * Delivers a password reset link to the account owner (UC-03 B2).
 *
 * <p>A port rather than a concrete mail client, because this module must not depend on an
 * outbound mail provider to compile or test. UC-03 says the raw token is sent to the account's
 * email address and never returned to the caller, so the delivery mechanism is the only place
 * the token may appear in plain form.
 *
 * <p>Implementations must not log the token or the link
 * (section 7.6). Whether a send succeeds must never change the HTTP response either: UC-03 B3
 * requires the same generic message whether or not the address exists, and a delivery failure
 * would otherwise reveal it.
 */
public interface PasswordResetNotifier {

    /**
     * @param email     the recipient address
     * @param resetLink the full link, including the raw token as a query parameter
     */
    void sendPasswordResetLink(String email, String resetLink);
}
