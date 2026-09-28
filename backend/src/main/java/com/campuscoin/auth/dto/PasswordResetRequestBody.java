package com.campuscoin.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequestBody(

        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be a well-formed email address.")
        @Size(max = 190, message = "Email must be at most 190 characters.")
        String email) {
}
