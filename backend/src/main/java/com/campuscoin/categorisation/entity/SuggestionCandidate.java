package com.campuscoin.categorisation.entity;

import com.campuscoin.category.entity.CategoryType;

public record SuggestionCandidate(
        Long categoryId,
        String categoryName,
        CategoryType type,
        boolean isDefault) {
}
