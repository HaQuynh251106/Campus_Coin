package com.campuscoin.tips.entity;

import java.math.BigDecimal;

public record TipRow(
        Long tipId,
        Long categoryId,
        String title,
        String body,
        BigDecimal potentialSaving,
        TipState state) {
}
