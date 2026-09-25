package com.campuscoin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/password-reset/verify} (UC-03 B5).
 *
 * <p>The raw token is sent <b>in the body, never in the URL</b>. A query parameter would be
 * written to access logs, proxy logs and the browser history by components that this application
 * does not control, and section 7.6 requires the token never to be logged.
 */
public record PasswordResetTokenRequest(

        @NotBlank(message = "Reset token is required.")
        @Size(max = 200, message = "Reset token is not valid.")
        String token) {
}
