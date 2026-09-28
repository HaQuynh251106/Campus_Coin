package com.campuscoin.imports.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.category.entity.CategoryType;

public record ImportRowDraft(
        int csvRowNo,
        String rawData,
        LocalDate parsedDate,
        BigDecimal parsedAmount,
        CategoryType parsedType,
        String parsedDescription,
        String parsedCategoryName,
        Long aiSuggestedCategoryId,
        ImportRowStatus rowStatus,
        String errorMessage) {
}
