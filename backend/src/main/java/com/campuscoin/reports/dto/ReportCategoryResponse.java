package com.campuscoin.reports.dto;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One category's total for one month, as a slice of the report (UC-15).
 *
 * <p><b>{@code total}, {@code transactionCount}, {@code categoryName} and {@code categoryId} are
 * {@code v_category_month_totals}' own columns.</b> The same view backs the dashboard's
 * "highest-spending category" widget, so the Food slice on this screen and the category named on the
 * home screen are one computation - a student who sees 24.00 here sees 24.00 there, by construction
 * rather than by both being right.
 *
 * <p>{@code categoryIcon} and {@code categoryColor} come from {@code categories} through a join in
 * the DAO, because the view publishes neither. Both are optional: {@code categories.icon} and
 * {@code categories.color} are nullable columns, and a category saved without them (a student's own,
 * or an administrator's) must still produce a slice. Omitting the two rather than substituting a
 * default keeps the client free to choose its own placeholder and keeps the response honest about
 * what the database holds.
 *
 * <p><b>{@code percentage} is the one figure this module computes, and it is here because nothing in
 * the schema computes it.</b> {@code v_category_month_totals} publishes the total and the count but no
 * share of a month, and a pie chart needs one. The denominator is the sum of the totals in the same
 * block - expense categories for {@code expenseByCategory}, income categories for
 * {@code incomeByCategory} - so each share is that slice's proportion of its own block and a client
 * can draw an arc without adding up the array itself. It is rounded to two decimals, matching the
 * schema's own convention for derived percentages ({@code v_budget_consumption.consumed_pct},
 * {@code v_dashboard_summary.savings_goal_pct}); a client that wants whole numbers for a legend
 * rounds for display.
 *
 * <p><b>The shares of one block do not in general add up to exactly 100.</b> Each is rounded on its
 * own, because a slice's percentage is a property of that slice, so three equal thirds each read
 * {@code 33.33} and total {@code 99.99}. No slice absorbs the remainder: publishing one category's
 * share as a figure that is not its share would be exactly the kind of wrong number a report cannot
 * afford, and the shortfall is bounded at under one unit of the last decimal place per slice.
 * {@code total} is exact and is what a whole pie should be drawn from.
 *
 * <p>{@code type} is published because a report may mix the two blocks client-side, and because it
 * lets one array shape serve both: a reader can tell an income slice from a spending slice without
 * knowing which array it arrived in.
 */
@Schema(description = "One category's total for the month, as a slice of the report (UC-15).")
public record ReportCategoryResponse(

        @Schema(description = "The category's identifier.", example = "1")
        Long categoryId,

        @Schema(description = "The category's name.", example = "Food")
        String categoryName,

        @Schema(description = "Icon name of that category, for the client to resolve. Omitted when "
                + "the category has none.", example = "utensils", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryIcon,

        @Schema(description = "Hex colour of that category. Omitted when the category has none.",
                example = "#F97316", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String categoryColor,

        @Schema(description = "Whether this category's records are income or spending (BR-05).",
                example = "EXPENSE")
        CategoryType type,

        @Schema(description = "Total recorded in that category that month, counting only records "
                + "that are not in the trash (BR-09).", example = "24.00")
        BigDecimal total,

        @Schema(description = "This category's own share of the block it appears in, as a percentage "
                + "to two decimal places. Shares are rounded independently and so need not total "
                + "exactly 100.", example = "12.70")
        BigDecimal percentage,

        @Schema(description = "How many live records make up the total.", example = "3")
        Long transactionCount) {
}
