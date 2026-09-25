package com.campuscoin.budget.dto;

import java.math.BigDecimal;

import com.campuscoin.budget.entity.ConsumptionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One monthly budget as the client sees it (UC-13), with how much of it has been used.
 *
 * <p><b>The limit and the consumption are published together, and the consumption is the
 * database's answer rather than this module's.</b> {@code spentAmount}, {@code remainingAmount},
 * {@code consumedPct} and {@code consumptionStatus} all come from {@code v_budget_consumption},
 * which is also what {@code sp_check_budget_alerts} compares against. Recomputing any of them in
 * Java would create a second definition of "80% of the limit" that could drift from the one that
 * decides when an alert fires - and a screen that showed a student on track while the alert log said
 * they had been warned is exactly the sort of disagreement this avoids.
 *
 * <p><b>The category is flattened, exactly as on a transaction and a recurring rule.</b> That is the
 * shape the Angular budget model already expects and the shape a list row renders, so a budget row
 * needs nothing else about the category.
 *
 * <p><b>{@code periodMonth} is a {@code yyyy-MM} string.</b> The column is a {@code DATE} pinned to
 * the first of the month ({@code ck_budget_month}), but the unit the student chose is a month, and
 * the day is an implementation detail of how a month is stored rather than something to show. The
 * conversion happens in {@code BudgetMapper} in both directions.
 *
 * <p><b>{@code userId} is absent, along with the row's timestamps</b>, for the reasons every other
 * response in the API gives: the owner is the account in the token and the client must never send
 * one back, and no use case shows creation metadata.
 */
@Schema(description = "A monthly spending limit for one category, with the month's consumption "
        + "(UC-13).")
public record BudgetResponse(

        @Schema(description = "Identifier, as the database assigned it.", example = "3")
        Long id,

        @Schema(description = "The expense category this limit applies to.", example = "1")
        Long categoryId,

        @Schema(description = "Name of that category.", example = "Food & Drinks")
        String categoryName,

        @Schema(description = "Icon name of that category, for the client to resolve. Omitted when "
                + "the category has none.", example = "utensils", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryIcon,

        @Schema(description = "Hex colour of that category. Omitted when the category has none.",
                example = "#F97316", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryColor,

        @Schema(description = "The month this limit covers, as `yyyy-MM`.", example = "2026-09")
        String periodMonth,

        @Schema(description = "The limit for the month.", example = "300.00")
        BigDecimal limitAmount,

        @Schema(description = "How much has been spent in this category this month, counting only "
                + "records that are not in the trash (BR-09).", example = "244.50")
        BigDecimal spentAmount,

        @Schema(description = "How much of the limit is left. Negative once the limit is passed.",
                example = "55.50")
        BigDecimal remainingAmount,

        @Schema(description = "Consumption as a percentage of the limit, to two decimal places. "
                + "`0` for a month with no spending.", example = "81.50")
        BigDecimal consumedPct,

        @Schema(description = "Derived from `consumedPct` and the configured thresholds: "
                + "`NEAR` at or above `budget.near_threshold_pct` (80% by default), `EXCEEDED` at or "
                + "above `budget.exceeded_threshold_pct` (100%), `ON_TRACK` below both.",
                example = "NEAR")
        ConsumptionStatus consumptionStatus) {
}
