package com.campuscoin.dashboard.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

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
