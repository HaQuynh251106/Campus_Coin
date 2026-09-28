package com.campuscoin.budget.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "A monthly spending limit to set (UC-13).")
public record CreateBudgetRequest(

        @Schema(description = "The expense category to limit. Must be one of your own categories or "
                + "a shared default one that is still in use, and must be an EXPENSE category - an "
                + "INCOME id is refused. `6` is `Food`, the first expense category "
                + "`db/05_seed.sql` creates.", example = "6")
        @NotNull(message = "Category is required.")
        Long categoryId,

        @Schema(description = "The month the limit covers, as `yyyy-MM`. Omit for the current "
                + "month.", example = "2026-09", nullable = true)

        @Pattern(regexp = "^\\s*\\d{4}-\\d{2}\\s*$",
                message = "Month must be in yyyy-MM form, for example 2026-09.")
        String periodMonth,

        @Schema(description = "The limit for the month. Strictly greater than zero, at most two "
                + "decimal places.", example = "300.00")
        @NotNull(message = "Limit amount is required.")

        @DecimalMin(value = "0.00", inclusive = false,
                message = "Limit amount must be greater than zero.")

        @Digits(integer = 13, fraction = 2,
                message = "Limit amount must be at most 9,999,999,999,999.99 with two decimal "
                        + "places.")
        BigDecimal limitAmount) {
}
