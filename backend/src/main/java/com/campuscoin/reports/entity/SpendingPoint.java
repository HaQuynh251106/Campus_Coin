package com.campuscoin.reports.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SpendingPoint(
        LocalDate intervalStart,
        LocalDate intervalEnd,
        BigDecimal totalExpense,
        Long transactionCount) {
}
