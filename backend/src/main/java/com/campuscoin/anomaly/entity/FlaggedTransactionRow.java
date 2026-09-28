package com.campuscoin.anomaly.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.campuscoin.category.entity.CategoryType;

public record FlaggedTransactionRow(
        Long transactionId,
        Long categoryId,
        String categoryName,
        CategoryType categoryType,
        BigDecimal amount,
        LocalDate txnDate,
        String encryptedDescription,
        Boolean isFlagged,
        AnomalyFlagType flagType,
        String flagNote) {
}
