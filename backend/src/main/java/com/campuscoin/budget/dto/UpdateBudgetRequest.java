package com.campuscoin.budget.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

/**
 * Body of {@code PATCH /api/v1/budgets/{id}} (UC-13): change a limit.
 *
 * <p><b>One field, and that is the whole contract.</b> {@code limitAmount} is the only part of a
 * budget a student can meaningfully change. The other three columns - the student, the category and
 * the month - are the row's identity, and {@code uk_budget_user_cat_month} is built from exactly
 * them (BR-11). Moving a limit onto another category or month is not an edit of this budget but a
 * request for a different one, which delete-then-create already expresses; and a writable
 * {@code categoryId} would additionally be a second way to put a limit on an income category,
 * slipping past the BR-11 check the insert trigger makes and updating no trigger repeats for a
 * value it was given at creation.
 *
 * <p>The field is optional, so a request that sends nothing is a no-op that returns the budget
 * unchanged. That is deliberate rather than an oversight: the same "leaves the rest as they are"
 * rule the other {@code PATCH} endpoints state, where a body that happens to contain only fields
 * the caller did not change does not become an error. In practice a client sends
 * {@code limitAmount}, because it is the only thing there is to send.
 *
 * <p>No {@code periodMonth} and no {@code categoryId} shortcuts: neither is a field of this
 * operation, and offering one "just for convenience" is how the duplicate the inventory forbids
 * would start.
 */
@Schema(description = "The budget field to change (UC-13).")
public record UpdateBudgetRequest(

        @Schema(description = "New limit for the month. Strictly greater than zero, at most two "
                + "decimal places.", example = "350.00", nullable = true)
        @DecimalMin(value = "0.00", inclusive = false,
                message = "Limit amount must be greater than zero.")
        @Digits(integer = 13, fraction = 2,
                message = "Limit amount must be at most 9,999,999,999,999.99 with two decimal "
                        + "places.")
        BigDecimal limitAmount) {
}
