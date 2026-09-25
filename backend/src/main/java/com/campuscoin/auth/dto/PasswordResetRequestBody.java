package com.campuscoin.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/password-reset/request} (UC-03 B2).
 *
 * <p>Only the email address is accepted. The response is the same generic message whether or not
 * an account exists for it (UC-03 B3, A2), so this request can never be used to find out which
 * addresses are registered.
 */
public record PasswordResetRequestBody(

        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be a well-formed email address.")
        @Size(max = 190, message = "Email must be at most 190 characters.")
        String email) {
}
