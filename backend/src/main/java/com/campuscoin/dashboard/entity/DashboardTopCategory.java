package com.campuscoin.dashboard.entity;

import java.math.BigDecimal;

public record DashboardTopCategory(
        Long categoryId,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        BigDecimal totalAmount) {
}
