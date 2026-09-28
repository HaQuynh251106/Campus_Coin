package com.campuscoin.recurring.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.recurring.entity.RecurringFrequency;
import com.campuscoin.recurring.entity.RecurringStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@Schema(description = "The recurring rule fields to change. Omit a field to leave it as it is "
        + "(UC-09).")
public record UpdateRecurringRuleRequest(

        @Schema(description = "Move the rule to another category, which also changes whether it "
                + "posts income or expense (BR-05). The new category must be in use.", example = "2",
                nullable = true)
        Long categoryId,

        @Schema(description = "New amount for each occurrence. Strictly greater than zero.",
                example = "220.00", nullable = true)
        @DecimalMin(value = "0.00", inclusive = false,
                message = "Amount must be greater than zero.")
        @Digits(integer = 13, fraction = 2,
                message = "Amount must be at most 9,999,999,999,999.99 with two decimal places.")
        BigDecimal amount,

        @Schema(description = "New note. Send an empty string to clear it.",
                example = "Monthly allowance", nullable = true)
        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description,

        @Schema(description = "How often the rule posts. Changing this does not re-post periods "
                + "already covered.", example = "MONTHLY", nullable = true)
        RecurringFrequency frequency,

        @Schema(description = "How many periods between occurrences. At least 1.", example = "1",
                nullable = true)
        @Min(value = 1, message = "Interval must be at least 1.")
        @Max(value = 999, message = "Interval must be at most 999.")
        Integer intervalCount,

        @Schema(description = "New end date, inclusive, as `yyyy-MM-dd`. Send an empty string to "
                + "remove it and make the rule open-ended.", example = "2027-06-01", nullable = true)

        @Pattern(regexp = "^\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*$",
                message = "End date must be a date in yyyy-MM-dd form, or empty for no end date.")
        String endDate,

        @Schema(description = "Date of the next occurrence, which is how a rule is moved onto a "
                + "different day.", example = "2026-10-15", nullable = true)
        LocalDate nextRunDate,

        @Schema(description = "Pause, resume or end the rule. `PAUSED` stops it posting without "
                + "losing it; `ENDED` stops it for good and is final - an ended rule cannot be "
                + "restarted, and asking to answers 409 RECURRING_RULE_ENDED.", example = "PAUSED",
                nullable = true)
        RecurringStatus status) {
}
