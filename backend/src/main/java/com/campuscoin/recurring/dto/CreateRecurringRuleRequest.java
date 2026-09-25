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

/**
 * Body of {@code POST /api/v1/recurring-rules} (UC-09): set up a repeating income or expense.
 *
 * <p><b>There is no {@code type} field, and that is the central decision here as it was for
 * transactions.</b> {@code recurring_rules} does keep a {@code type} column - it has to, because a
 * rule can be created before any transaction exists and BR-05 needs something to compare against
 * the category at that moment - but the client still does not send it. The category decides
 * (BR-05), {@code trg_recurring_rules_before_insert} enforces the agreement, and the service writes
 * the category's value. A schema column is not a reason to expose a field.
 *
 * <p><b>Deliberately absent, all of them either the server's or a later module's:</b>
 *
 * <ul>
 *   <li>{@code userId} - the owner is the account in the bearer token.</li>
 *   <li>{@code status} - every rule is created {@code ACTIVE}. An endpoint that accepted a status
 *       would be a second way to do what pause and end already do, and would let a client create a
 *       rule that is already stopped.</li>
 *   <li>{@code lastRunDate} - written only by {@code sp_post_recurring_transactions}. A client able
 *       to set it could make the rule's history claim a period was posted when it was not.</li>
 *   <li>{@code dayOfMonth}, {@code dayOfWeek} - schema columns the scheduler does not read. See
 *       {@link com.campuscoin.recurring.entity.RecurringRule} for why they are not written at all
 *       rather than accepted and ignored.</li>
 * </ul>
 *
 * <p>{@code startDate} is required and is not editable afterwards - it is the rule's origin, and the
 * periods already posted are a function of it. {@code nextRunDate} is optional: left out, the rule
 * first runs on its start date, which is what a student setting up "my rent every month from the
 * 1st" expects. Sending it schedules the first run later than the start, which is how a rule is set
 * up in advance without it firing on creation.
 */
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
        // ck_recurring_amount requires > 0, so 0 is refused here rather than by the database.
        @DecimalMin(value = "0.00", inclusive = false,
                message = "Amount must be greater than zero.")
        // amount DECIMAL(15,2): thirteen digits before the point and two after.
        @Digits(integer = 13, fraction = 2,
                message = "Amount must be at most 9,999,999,999,999.99 with two decimal places.")
        BigDecimal amount,

        @Schema(description = "Optional note copied onto every transaction this rule posts. An "
                + "empty string stores no description.", example = "Monthly allowance",
                nullable = true)
        // (?s) so a multi-line note is accepted, and the limit is measured after the trim the
        // service applies - see the same pattern in the transaction and category modules.
        @Pattern(regexp = "(?s)^\\s*.{0,255}\\s*$",
                message = "Description must be at most 255 characters.")
        String description,

        @Schema(description = "How often the rule posts.", example = "MONTHLY")
        @NotNull(message = "Frequency is required.")
        RecurringFrequency frequency,

        @Schema(description = "How many periods between occurrences: 1 is every period, 2 with "
                + "MONTHLY is every other month. Omit for 1.", example = "1", nullable = true)
        @Min(value = 1, message = "Interval must be at least 1.")
        // smallint unsigned holds up to 65535. A bound here keeps an over-large value a field
        // error rather than a refused write, and 999 is already far past any real schedule.
        @Max(value = 999, message = "Interval must be at most 999.")
        Integer intervalCount,

        @Schema(description = "The date the rule starts from. Cannot be changed afterwards.",
                example = "2026-09-01")
        @NotNull(message = "Start date is required.")
        LocalDate startDate,

        @Schema(description = "The date the rule stops, inclusive, as `yyyy-MM-dd`. Omit or send an "
                + "empty string for a rule that runs until you end it.", example = "2027-06-01",
                nullable = true)
        // Typed as a String rather than a LocalDate, and the same in the update request, because
        // this is the one date in the rule that can be REMOVED: sending "" clears it. A record of
        // nullable fields cannot tell "absent" from "explicit null" - both arrive as null - so a
        // typed date would leave no way to express "no end date" after one had been set. The
        // project already uses "" for exactly this purpose on the profile module's academicYear,
        // and a JSON date is a string on the wire in any case. See UpdateRecurringRuleRequest.
        @Pattern(regexp = "^\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*$",
                message = "End date must be a date in yyyy-MM-dd form, or empty for no end date.")
        String endDate,

        @Schema(description = "The date of the first occurrence. Omit to start on startDate.",
                example = "2026-10-01", nullable = true)
        LocalDate nextRunDate) {
}
