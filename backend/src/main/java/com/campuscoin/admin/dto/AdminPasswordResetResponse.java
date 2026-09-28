package com.campuscoin.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Confirmation that a reset link was sent (UC-22 B4).")
public record AdminPasswordResetResponse(

        @Schema(description = "A fixed message. The reset link is sent to the account's email "
                + "address and is never returned here.",
                example = "A password reset link has been sent to the account's email address.")
        String message) {
}
