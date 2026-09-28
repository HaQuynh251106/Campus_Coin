package com.campuscoin.profile.dto;

import java.math.BigDecimal;

import com.campuscoin.auth.entity.FontScale;
import com.campuscoin.auth.entity.ThemePreference;

import io.swagger.v3.oas.annotations.media.Schema;

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
