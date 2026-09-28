package com.campuscoin.budget.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BudgetConsumption(
        Long budgetId,
        Long categoryId,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        LocalDate periodMonth,
        BigDecimal limitAmount,
        BigDecimal spentAmount,
        BigDecimal remainingAmount,
        BigDecimal consumedPct,
        ConsumptionStatus consumptionStatus) {
}
