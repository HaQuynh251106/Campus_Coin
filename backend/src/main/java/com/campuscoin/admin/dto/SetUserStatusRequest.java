package com.campuscoin.admin.dto;

import com.campuscoin.auth.entity.AccountStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "The status to move an account to (UC-22 B3).")
public record SetUserStatusRequest(

        @Schema(description = "`DISABLED` blocks sign-in and immediately invalidates the "
                + "account's existing tokens and sessions. `ACTIVE` restores access. An "
                + "administrator cannot disable their own account.",
                example = "DISABLED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Choose a status: ACTIVE or DISABLED.")
        AccountStatus status) {
}
