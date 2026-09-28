package com.campuscoin.reports.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MonthlyTrendPoint(
        LocalDate periodMonth,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net) {
}
