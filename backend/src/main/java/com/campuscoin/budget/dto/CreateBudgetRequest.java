package com.campuscoin.budget.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /api/v1/budgets} (UC-13): set a monthly spending limit for a category.
 *
 * <p><b>Deliberately absent:</b>
 *
 * <ul>
 *   <li>{@code userId} - the owner is the account in the bearer token (BR-02).</li>
 *   <li>Any spend or status field - {@code spentAmount}, {@code consumedPct} and
 *       {@code consumptionStatus} are derived by {@code v_budget_consumption}, and a client able to
 *       send one could show itself on track while the alert log disagreed.</li>
 *   <li>{@code type} - a budget is only ever on an expense category (BR-11), which
 *       {@code sp_validate_budget} enforces through the insert trigger. There is no value to send:
 *       the category decides, and an income category is simply refused.</li>
 * </ul>
 *
 * <p><b>{@code periodMonth} is a string, not a date.</b> A student sets a limit for a month, and
 * {@code yyyy-MM} is how they name one. The service turns it into the first of that month, which is
 * what {@code ck_budget_month} requires; accepting a full date would invite a caller to send the
 * 15th and receive a constraint violation instead of an answer. It is optional: omitted, the limit
 * applies to the current month, which is the common case and the one a dashboard's "set a budget"
 * button means.
 *
 * <p>{@code limitAmount} is required and must be strictly positive. {@code ck_budget_limit} is the
 * authority; the annotation is here so the caller gets a field error rather than a refused write.
 */
@Schema(description = "A monthly spending limit to set (UC-13).")
public record CreateBudgetRequest(

        @Schema(description = "The expense category to limit. Must be one of your own categories or "
                + "a shared default one that is still in use.", example = "1")
        @NotNull(message = "Category is required.")
        Long categoryId,

        @Schema(description = "The month the limit covers, as `yyyy-MM`. Omit for the current "
                + "month.", example = "2026-09", nullable = true)
        // Anchored so "2026-09-15" and "2026-9" are refused rather than quietly accepted and
        // truncated; the service still checks that the month is a real one, since 2026-13 matches
        // this pattern.
        @Pattern(regexp = "^\\s*\\d{4}-\\d{2}\\s*$",
                message = "Month must be in yyyy-MM form, for example 2026-09.")
        String periodMonth,

        @Schema(description = "The limit for the month. Strictly greater than zero, at most two "
                + "decimal places.", example = "300.00")
        @NotNull(message = "Limit amount is required.")
        // ck_budget_limit requires > 0, so 0 is refused here rather than by the database.
        @DecimalMin(value = "0.00", inclusive = false,
                message = "Limit amount must be greater than zero.")
        // DECIMAL(15,2): thirteen digits before the point and two after.
        @Digits(integer = 13, fraction = 2,
                message = "Limit amount must be at most 9,999,999,999,999.99 with two decimal "
                        + "places.")
        BigDecimal limitAmount) {
}
