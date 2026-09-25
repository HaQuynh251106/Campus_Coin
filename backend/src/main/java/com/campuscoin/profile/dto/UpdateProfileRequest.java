package com.campuscoin.profile.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code PATCH /api/v1/profile/me} (UC-04).
 *
 * <p>Every field is optional and an absent field means "leave this alone", because UC-04 edits
 * four values that a client may well change one at a time - raising the savings goal should not
 * require resending the name. An explicit {@code null} means the same thing here, so absent and
 * {@code null} are equivalent: the service skips a field that is not present.
 *
 * <p>{@code academicYear} is the one exception, and it is a deliberate asymmetry. It is the only
 * nullable column of the four, so it needs a way to be unset, and it uses the empty string for
 * that: {@code ""} clears it. The other three have no empty state - there is no meaningful blank
 * name or allowance - so an empty string there is a validation error rather than a silent clear.
 *
 * <p>Four fields, and no more. There is no {@code email}, no {@code password}, no {@code role} and
 * no {@code status}: UC-04 does not describe changing any of them, and accepting them would be a
 * privilege-escalation route. Currency is absent for the same reason - VĐ-08 makes it one
 * application-wide setting rather than a per-student preference.
 */
@Schema(description = "The profile fields to change. Omit a field to leave it as it is (UC-04).")
public record UpdateProfileRequest(

        @Schema(description = "New display name. Omit to leave it unchanged; it cannot be blank.",
                example = "An Nguyen", nullable = true)
        // A pattern rather than @NotBlank, because @NotBlank fails on null and null here means
        // "leave the name as it is". The pattern passes null, and still rejects "" and "   ".
        //
        // The surrounding \s* groups are not decoration: the service trims the value before
        // storing it, so a plain {2,120} would let " A " through and then write the 1-character
        // name "A". Measuring the length between a non-space at each end - with any amount of
        // surrounding whitespace ignored - keeps the length actually stored, which is what the
        // client sees echoed back, inside the documented range. Spaces inside the name are left
        // alone, since "An Nguyen" and "An  Nguyen" are both legitimate input.
        @Pattern(regexp = "^\\s*\\S.{0,118}\\S\\s*$",
                message = "Full name must be between 2 and 120 characters and cannot be blank.")
        String fullName,

        @Schema(description = "Year of study. Send an empty string to clear it.", example = "Year 3",
                nullable = true)
        // Measured the same way as fullName: the service trims before storing, so the limit
        // applies to the value that is actually written to academic_year VARCHAR(30). A plain
        // @Size(max = 30) would measure the raw input and reject "  Year 3  " padding that trims
        // back inside the column - the documented rule is the length after trimming. The empty
        // string is allowed on purpose, because sending it is how the field is cleared.
        @Pattern(regexp = "^\\s*.{0,30}\\s*$",
                message = "Academic year must be at most 30 characters.")
        String academicYear,

        @Schema(description = "Expected monthly allowance (VĐ-04).", example = "650.00",
                nullable = true)
        // The lower bound mirrors ck_users_money, so an invalid amount is reported per field
        // rather than surfacing later as a generic constraint violation.
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
