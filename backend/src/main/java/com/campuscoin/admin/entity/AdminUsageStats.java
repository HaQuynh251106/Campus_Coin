package com.campuscoin.admin.entity;

import java.math.BigDecimal;

/**
 * The single row of {@code v_admin_usage_stats} (UC-23).
 *
 * <p><b>{@code total_expense_logged} and {@code total_income_logged} are {@code SUM(amount)} over
 * plaintext transaction amounts, and that is a recorded decision rather than an oversight.</b>
 * Amounts are the one sensitive field the encryption pass deliberately left in the clear, because
 * every reporting, budgeting and tip rule in the system sums them in SQL and re-deriving those
 * aggregates in the application was out of scope (OB-013). The exposure this view adds is an
 * <em>aggregate</em>: it is the total across every student, and it is not reachable per student.
 * The position is recorded in {@code docs/OVERNIGHT_BLOCKERS.md} rather than hidden, and no
 * endpoint in this module returns an individual student's amounts.
 *
 * <p>{@code total_insights_generated} is carried although this build never generates an insight:
 * the view counts the {@code insights} table, which is empty, and the figure is a true zero. The
 * response reports what the view reports rather than omitting a column it computed - a caller
 * reading the row should see the same numbers whichever view answered.
 */
public record AdminUsageStats(
        Long totalStudents,
        Long activeStudents,
        Long disabledStudents,
        Long activeUsers30d,
        Long totalTransactions,
        BigDecimal totalExpenseLogged,
        BigDecimal totalIncomeLogged,
        Long totalBudgets,
        Long totalTipsGenerated,
        Long totalInsightsGenerated) {
}
