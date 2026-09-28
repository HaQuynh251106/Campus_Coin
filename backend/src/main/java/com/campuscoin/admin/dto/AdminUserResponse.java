package com.campuscoin.admin.dto;

import java.time.LocalDateTime;

import com.campuscoin.auth.entity.AccountStatus;
import com.campuscoin.auth.entity.UserRole;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An account as the administration screen lists it (UC-22).")
public record AdminUserResponse(

        @Schema(description = "Identifier, as the database assigned it. The id, address and name in "
                + "this example are one account seeded by `db/05_seed.sql`; an id read from this "
                + "response is always the account it came back with.", example = "3")
        Long id,

        @Schema(description = "Sign-in address. Published only to an administrator.",
                example = "binh.tran@student.campuscoin.edu")
        String email,

        @Schema(description = "Name shown across the application. This is the account the id and "
                + "address above belong to.", example = "Bella Tran")
        String fullName,

        @Schema(description = "`STUDENT` or `ADMIN`. Only an administrator reaches this endpoint.",
                example = "STUDENT")
        UserRole role,

        @Schema(description = "`ACTIVE` or `DISABLED`. A disabled account cannot sign in and its "
                + "existing tokens are refused (BR-03).", example = "ACTIVE")
        AccountStatus status,

        @Schema(description = "Year of study, a free label such as `Year 3`. Omitted when the "
                + "student has not set one.", example = "Year 3", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String academicYear,

        @Schema(description = "When the account last signed in. Omitted when it never has.",
                example = "2026-09-24T08:30:00", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime lastLoginAt,

        @Schema(description = "When the account was created.", example = "2026-08-01T10:00:00")
        LocalDateTime createdAt) {
}
