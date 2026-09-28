package com.campuscoin.insight.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record InsightRow(
        LocalDate periodMonth,
        String summaryText,
        String adviceText,
        List<FlaggedCategory> flaggedCategories,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal netAmount,
        InsightGeneratedBy generatedBy,
        String modelName,
        LocalDateTime generatedAt) {

    public record FlaggedCategory(
            Long categoryId,
            String categoryName,
            BigDecimal currentTotal,
            BigDecimal baselineAvg,
            BigDecimal pctChange) {
    }
}
