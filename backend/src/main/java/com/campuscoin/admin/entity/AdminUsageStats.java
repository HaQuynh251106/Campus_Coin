package com.campuscoin.admin.entity;

import java.math.BigDecimal;

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
