package com.campuscoin.profile.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The profile fields to change. Omit a field to leave it as it is (UC-04).")
public record UpdateProfileRequest(

        @Schema(description = "New display name. Omit to leave it unchanged; it cannot be blank.",
                example = "An Nguyen", nullable = true)

        @Pattern(regexp = "^\\s*\\S.{0,118}\\S\\s*$",
                message = "Full name must be between 2 and 120 characters and cannot be blank.")
        String fullName,

        @Schema(description = "Year of study. Send an empty string to clear it.", example = "Year 3",
                nullable = true)

        @Pattern(regexp = "^\\s*.{0,30}\\s*$",
                message = "Academic year must be at most 30 characters.")
        String academicYear,

        @Schema(description = "Expected monthly allowance (VĐ-04).", example = "650.00",
                nullable = true)

        @DecimalMin(value = "0.00", message = "Monthly allowance baseline cannot be negative.")
        @Digits(integer = 13, fraction = 2,
                message = "Monthly allowance baseline must have at most 13 digits and 2 decimal places.")
        BigDecimal monthlyAllowanceBaseline,

        @Schema(description = "Monthly saving target (VĐ-04).", example = "1500.00", nullable = true)
        @DecimalMin(value = "0.00", message = "Monthly savings goal cannot be negative.")
        @Digits(integer = 13, fraction = 2,
                message = "Monthly savings goal must have at most 13 digits and 2 decimal places.")
        BigDecimal monthlySavingsGoal) {
}
