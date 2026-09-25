package com.campuscoin.auth.dto;

/**
 * Result of {@code POST /api/v1/auth/password-reset/verify} (UC-03 B5).
 *
 * <p>Returned only when the token is usable; an unknown, already-used or expired token is
 * answered with 400 and error code {@code INVALID_RESET_TOKEN} (BR-04, UC-03 A1). The field is
 * therefore always {@code true} on a 200 and exists so the response is self-describing rather
 * than an empty body the client has to interpret from the status code alone.
 *
 * <p>No account detail is included. Identifying the account to the holder of a link would tell
 * whoever intercepted it whose password they are about to change, and the client does not need
 * that to open the new-password screen.
 */
public record PasswordResetVerifyResponse(boolean valid) {

    public static final PasswordResetVerifyResponse VALID = new PasswordResetVerifyResponse(true);
}
