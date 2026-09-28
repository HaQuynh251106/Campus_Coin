package com.campuscoin.categorisation.entity;

import java.math.BigDecimal;

import com.campuscoin.category.entity.CategoryType;

public record CategoryAdvice(
        SuggestionSource source,
        Long categoryId,
        String categoryName,
        CategoryType type,
        BigDecimal confidence,
        String reason) {
}
