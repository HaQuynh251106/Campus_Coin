package com.campuscoin.recurring.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.recurring.entity.RecurringFrequency;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "A recurring rule to create (UC-09).")
public record CreateRecurringRuleRequest(

        @Schema(description = "The category this rule posts under. Decides whether it is income or "
                + "expense (BR-05), and must be one of your own categories or a shared default "
                + "one that is still in use.", example = "1")
        @NotNull(message = "Category is required.")
        Long categoryId,

        @Schema(description = "How much each occurrence posts. Strictly greater than zero, at most "
                + "two decimal places.", example = "200.00")
        @NotNull(message = "Amount is required.")

        @DecimalMin(value = "0.00", inclusive = false,
                message = "Amount must be greater than zero.")

        @Digits(integer = 13, fraction = 2,
                message = "Amount must be at most 9,999,999,999,999.99 with two decimal places.")
        BigDecimal amount,

        @Schema(description = "Optional note copied onto every transaction this rule posts. An "
                + "empty string stores no description.", example = "Monthly allowance",
                nullable = true)

        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description,

        @Schema(description = "How often the rule posts.", example = "MONTHLY")
        @NotNull(message = "Frequency is required.")
        RecurringFrequency frequency,

        @Schema(description = "How many periods between occurrences: 1 is every period, 2 with "
                + "MONTHLY is every other month. Omit for 1.", example = "1", nullable = true)
        @Min(value = 1, message = "Interval must be at least 1.")

        @Max(value = 999, message = "Interval must be at most 999.")
        Integer intervalCount,

        @Schema(description = "The date the rule starts from. Cannot be changed afterwards.",
                example = "2026-09-01")
        @NotNull(message = "Start date is required.")
        LocalDate startDate,

        @Schema(description = "The date the rule stops, inclusive, as `yyyy-MM-dd`. Omit or send an "
                + "empty string for a rule that runs until you end it.", example = "2027-06-01",
                nullable = true)

        @Pattern(regexp = "^\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*$",
                message = "End date must be a date in yyyy-MM-dd form, or empty for no end date.")
        String endDate,

        @Schema(description = "The date of the first occurrence. Omit to start on startDate.",
                example = "2026-10-01", nullable = true)
        LocalDate nextRunDate) {
}
