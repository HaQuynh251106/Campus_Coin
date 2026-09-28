package com.campuscoin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
