package com.campuscoin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetTokenRequest(

        @NotBlank(message = "Reset token is required.")
        @Size(max = 200, message = "Reset token is not valid.")
        String token) {
}
