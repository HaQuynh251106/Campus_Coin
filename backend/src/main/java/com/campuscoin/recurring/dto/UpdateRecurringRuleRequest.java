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

/**
 * Body of {@code PATCH /api/v1/recurring-rules/{id}} (UC-09): change some fields of a rule.
 *
 * <p>Every field is optional and a field left out is not changed, so a client sends only what it
 * edited. An explicit {@code null} means the same thing, with one deliberate exception:
 * {@code endDate} is nullable in the schema, so sending it as an empty string ({@code ""}) removes
 * the end date and makes the rule open-ended again. That matches the convention the profile module
 * established for {@code academicYear}, which is the only other nullable non-text column in the
 * API - a date sent as a JSON string has no other way to express "clear this".
 *
 * <p>Shortcuts are deliberately <em>not</em> provided. There is no {@code pause} or {@code resume}
 * flag: both are {@code status}, and a second way to say the same thing would be a duplicate the
 * moment the two disagreed. There is likewise no separate endpoint for pausing - {@code PATCH} with
 * {@code {"status": "PAUSED"}} is the operation, and it is one the contract documents explicitly
 * because it is the most common thing a student does to a rule.
 *
 * <p><b>{@code type} is absent.</b> Moving a rule to another category is how it changes type
 * (BR-05) - exactly as in the transaction module - and the service re-derives the type from the
 * target category rather than accepting one. That is not just tidiness here: the update trigger
 * re-runs the BR-05 comparison on <em>every</em> update, so a move that did not also change the
 * type would be refused by the database. Deriving it makes that failure unrepresentable.
 *
 * <p><b>Also absent: {@code startDate} and {@code lastRunDate}.</b> {@code startDate} is the rule's
 * origin, and the periods already posted are derived from it, so moving it would make the rule
 * disagree with its own occurrence history. {@code nextRunDate} is the field that moves a rule onto
 * a different day, and it is here for that purpose. {@code lastRunDate} is written by the scheduler
 * alone.
 */
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
        // A String rather than a LocalDate, matching the create request, because this is the field
        // that can be REMOVED. The usual convention in this API is that an absent field and an
        // explicit null both mean "leave it as it is", which a nullable record cannot escape - so
        // with a typed date there would be no way to say "clear the end date" at all. The empty
        // string is that way, exactly as it is for the profile module's academicYear.
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
