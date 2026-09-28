package com.campuscoin.categorisation.entity;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

public record TransactionCategorisationRow(
        Long categoryId,
        CategoryType categoryType,
        String encryptedDescription,
        Long aiSuggestedCategoryId,
        BigDecimal aiConfidence,
        boolean aiOverridden) {
}
