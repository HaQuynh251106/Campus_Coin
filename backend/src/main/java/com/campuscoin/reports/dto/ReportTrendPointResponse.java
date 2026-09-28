package com.campuscoin.reports.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One month of the six-month trend (UC-15, BR-17).")
public record ReportTrendPointResponse(

        @Schema(description = "The month this point describes, as `yyyy-MM`.", example = "2026-09")
        String periodMonth,

        @Schema(description = "Total income that month. `0.00` when the month holds no records.",
                example = "260.00")
        BigDecimal income,

        @Schema(description = "Total spending that month. `0.00` when the month holds no records.",
                example = "189.00")
        BigDecimal expense,

        @Schema(description = "Income minus spending. `0.00` when the month holds no records.",
                example = "71.00")
        BigDecimal net) {
}
