package com.campuscoin.reports.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReportTotals(
        LocalDate periodMonth,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net,
        Long transactionCount) {

    public static ReportTotals empty(LocalDate periodMonth) {
        return new ReportTotals(periodMonth, null, null, null, null);
    }
}
