package com.campuscoin.profile.dto;

import java.math.BigDecimal;

import com.campuscoin.auth.entity.FontScale;
import com.campuscoin.auth.entity.ThemePreference;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The signed-in student's own profile and preferences (UC-04, UC-27).
 *
 * <p>Returned by {@code GET /api/v1/profile/me} and by both update endpoints, so a client always
 * receives the complete current state after a change instead of having to merge its own copy.
 *
 * <p>Nine fields, all of them the student's own. No {@code password_hash}, no
 * {@code token_version}, no {@code role}, no {@code status}: the first two are security material
 * and the last two are not the profile's to report - a student cannot change their own role or
 * account status, and echoing them back would invite a client to treat them as editable.
 *
 * <p>Every field is always present, including those that may be null. {@code academicYear} is
 * nullable in the schema, so a student who has not stated a year receives {@code null} rather
 * than a substituted default - the client decides how to render "not set", and the API does not
 * invent a value the student never entered.
 */
@Schema(description = "The signed-in student's profile and display preferences (UC-04, UC-27).")
public record ProfileResponse(

        @Schema(description = "Account identifier.", example = "2")
        Long id,

        @Schema(description = "Displayed name (UC-04).", example = "An Nguyen")
        String fullName,

        @Schema(description = "Sign-in address. Read-only here: changing it is not part of UC-04.",
                example = "an.nguyen@student.campuscoin.edu")
        String email,

        @Schema(description = "Year of study, or null when the student has not set one (UC-04).",
                example = "Year 3", nullable = true)
        String academicYear,

        @Schema(description = "Expected monthly allowance (VĐ-04, UC-04). Never negative.",
                example = "650.00")
        BigDecimal monthlyAllowanceBaseline,

        @Schema(description = "Monthly saving target (VĐ-04, UC-04). Never negative.",
                example = "1500.00")
        BigDecimal monthlySavingsGoal,

        @Schema(description = "Currency of this account, ISO 4217 (VĐ-08). Read-only.",
                example = "USD")
        String currency,

        @Schema(description = "Appearance preference (UC-27). SYSTEM follows the operating system.",
                example = "SYSTEM")
        ThemePreference themePreference,

        @Schema(description = "Text size preference (UC-27).", example = "MEDIUM")
        FontScale fontScale) {
}
