package com.campuscoin.dashboard.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The current month's totals for the signed-in student (UC-12 B1).
 *
 * <p><b>Every figure is the database's answer, read from {@code v_dashboard_summary}.</b> The
 * income/expense split comes from the category's type (BR-05), the totals count only records that are
 * not in the trash (BR-09), and {@code netAmount} is the view's own subtraction rather than one made
 * here. The dashboard is the first thing a student sees, so a figure computed twice is a figure that
 * will eventually disagree with the reports screen.
 *
 * <p><b>{@code savingsGoalPct} is omitted when there is no goal, not sent as zero.</b> The view
 * computes it only when {@code monthly_savings_goal > 0}, and the two states mean different things: a
 * goal of zero has no progress to report, whereas a student who set a goal and has spent their income
 * down has genuinely made negative progress. A substituted zero would collapse those and tell a
 * student with no goal that they had achieved nothing. It is nullable, so it is omitted rather than
 * serialised as null - the convention the earlier modules set.
 *
 * <p>{@code periodMonth} is not repeated here. It is the month the whole response describes and it is
 * published once, on {@link DashboardResponse} - a second copy would be a second value to keep in
 * step.
 */
@Schema(description = "The current month's totals and saving-goal progress (UC-12 B1).")
public record DashboardSummaryResponse(

        @Schema(description = "The currency these figures are in, as the account stores it.",
                example = "USD")
        String currency,

        @Schema(description = "Total income recorded this month, counting only records that are not "
                + "in the trash (BR-09).", example = "260.00")
        BigDecimal totalIncome,

        @Schema(description = "Total spending recorded this month, counting only records that are "
                + "not in the trash (BR-09).", example = "189.00")
        BigDecimal totalExpense,

        @Schema(description = "Income minus spending. Negative for a month that spent more than it "
                + "earned.", example = "71.00")
        BigDecimal netAmount,

        @Schema(description = "The monthly allowance the student recorded, as a reference figure. "
                + "`0.00` when none was set.", example = "500.00")
        BigDecimal monthlyAllowanceBaseline,

        @Schema(description = "The saving goal the student set for the month. `0.00` when none was "
                + "set, which is also when `savingsGoalPct` is absent.", example = "100.00")
        BigDecimal monthlySavingsGoal,

        @Schema(description = "Net amount as a percentage of the saving goal, to two decimal places. "
                + "Absent when no goal is set; may be negative when the month is net-negative.",
                example = "71.00", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal savingsGoalPct) {
}
