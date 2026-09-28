package com.campuscoin.recent.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.campuscoin.category.entity.CategoryType;

public record RecentActivityRow(
        Long transactionId,
        RecentAction action,
        LocalDateTime occurredAt,
        Long categoryId,
        CategoryType categoryType,
        BigDecimal amount,
        String encryptedDescription,
        LocalDate txnDate) {
}
