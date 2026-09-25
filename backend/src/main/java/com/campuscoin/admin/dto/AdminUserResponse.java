package com.campuscoin.admin.dto;

import java.time.LocalDateTime;

import com.campuscoin.auth.entity.AccountStatus;
import com.campuscoin.auth.entity.UserRole;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One account as the administrator's user list shows it (UC-22 B1).
 *
 * <p><b>The field list is the disclosure decision.</b> This is the only response in the API that
 * describes an account other than the caller's own, so it is worth stating what is here and, more
 * importantly, what is not:
 *
 * <ul>
 *   <li>{@code passwordHash} - never selected, never carried. BR-01.</li>
 *   <li>{@code tokenVersion} - never selected. It is the security meaning of a JWT's {@code tv}
 *       claim: publishing it would let a reader decide whether a stolen token is still live.</li>
 *   <li>{@code monthlyAllowanceBaseline} and {@code monthlySavingsGoal} - the student's own
 *       financial position (VĐ-04). UC-22 asks an administrator to manage <em>accounts</em>, not to
 *       see what a student earns or hopes to save, and there is no administrative action that needs
 *       either figure.</li>
 *   <li>{@code themePreference}, {@code fontScale}, {@code aiEnabled} (UC-27), and
 *       {@code emailVerifiedAt} - no use case on this screen.</li>
 * </ul>
 *
 * <p>{@code email} <em>is</em> published, and that is the deliberate exception. The administration
 * screen lists and searches by address, and UC-22 B4's reset flow is addressed to it. It is safe
 * only because the whole route family is behind {@code hasRole("ADMIN")}; see
 * {@code docs/api/administration.md} for why this module's disclosure position differs from the
 * bookmarks module's "not yours and does not exist look identical" rule.
 *
 * <p>{@code lastLoginAt} is carried because UC-23's "active users" figure is a statement about
 * recency, and the per-account value is what makes that figure legible beside a list of accounts.
 * It is omitted when the account has never signed in, rather than sent as {@code null}, so a client
 * can distinguish "never" from an unparsed date.
 */
@Schema(description = "An account as the administration screen lists it (UC-22).")
public record AdminUserResponse(

        @Schema(description = "Identifier, as the database assigned it.", example = "3")
        Long id,

        @Schema(description = "Sign-in address. Published only to an administrator.",
                example = "student@campus.edu")
        String email,

        @Schema(description = "Name shown across the application.", example = "Nguyen Van A")
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
