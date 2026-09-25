package com.campuscoin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/password-reset/complete} (UC-03 B7).
 *
 * <p>Carries the token from the reset link together with the new password and its confirmation.
 * The password rules match {@link RegisterRequest} so a student cannot set a password through the
 * reset flow that registration would have refused.
 *
 * <p>Confirmations are compared in the service, not with a class-level constraint, so the failure
 * is reported as a field error on {@code confirmPassword} - the same shape UC-01 A2 expects for
 * the registration form.
 */
public record PasswordResetCompleteRequest(

        @NotBlank(message = "Reset token is required.")
        @Size(max = 200, message = "Reset token is not valid.")
        String token,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters.")
        @Pattern(regexp = ".*[A-Z].*", message = "Password must contain at least one upper-case letter.")
        @Pattern(regexp = ".*[a-z].*", message = "Password must contain at least one lower-case letter.")
        @Pattern(regexp = ".*\\d.*", message = "Password must contain at least one digit.")
        String newPassword,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword) {
}
