package com.campuscoin.reports.entity;

import java.time.LocalDate;

public record ReportScope(
        LocalDate periodMonth,
        LocalDate from,
        LocalDate to,
        ReportGranularity granularity) {

    public LocalDate monthEnd() {
        return periodMonth.withDayOfMonth(periodMonth.lengthOfMonth());
    }
}
