package com.campuscoin.anomaly.entity;

import java.math.BigDecimal;

public record CategoryAmountStats(
        Long categoryId,
        long recordCount,
        BigDecimal totalAmount) {
}
