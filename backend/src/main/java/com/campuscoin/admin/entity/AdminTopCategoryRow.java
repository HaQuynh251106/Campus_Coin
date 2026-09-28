package com.campuscoin.admin.entity;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

public record AdminTopCategoryRow(
        Long categoryId,
        String categoryName,
        CategoryType type,
        String scope,
        Long txnCount,
        BigDecimal totalAmount,
        Long distinctUsers) {
}
