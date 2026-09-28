package com.campuscoin.reports.entity;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

public record CategoryBreakdownRow(
        Long categoryId,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        CategoryType type,
        BigDecimal totalAmount,
        Long transactionCount) {
}
