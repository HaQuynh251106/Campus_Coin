package com.campuscoin.dashboard.entity;

import java.math.BigDecimal;

public record DashboardTip(
        Long tipId,
        Long categoryId,
        String title,
        String body,
        BigDecimal potentialSaving,
        TipState state) {
}
