package com.campuscoin.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "Full name is required.")
        @Size(min = 2, max = 120, message = "Full name must be between 2 and 120 characters.")
        String fullName,

        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be a well-formed email address.")
        @Size(max = 190, message = "Email must be at most 190 characters.")
        String email,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters.")
        @Pattern(regexp = ".*[A-Z].*", message = "Password must contain at least one upper-case letter.")
        @Pattern(regexp = ".*[a-z].*", message = "Password must contain at least one lower-case letter.")
        @Pattern(regexp = ".*\\d.*", message = "Password must contain at least one digit.")
        String password,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword) {
}
