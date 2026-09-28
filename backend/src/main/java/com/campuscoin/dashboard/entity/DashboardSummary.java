package com.campuscoin.dashboard.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DashboardSummary(
        LocalDate periodMonth,
        String currency,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal netAmount,
        BigDecimal monthlyAllowanceBaseline,
        BigDecimal monthlySavingsGoal,
        BigDecimal savingsGoalPct) {
}
