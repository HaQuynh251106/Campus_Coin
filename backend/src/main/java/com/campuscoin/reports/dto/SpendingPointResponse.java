package com.campuscoin.reports.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One interval of a spending series: a day, or an ISO week (UC-15).")
public record SpendingPointResponse(

        @Schema(description = "The first day of the interval, as `yyyy-MM-dd`.", example = "2026-09-06")
        String intervalStart,

        @Schema(description = "The last day of the interval, inclusive, as `yyyy-MM-dd`. The same "
                + "day as `intervalStart` for a daily series.", example = "2026-09-06")
        String intervalEnd,

        @Schema(description = "Total spending in the interval, counting only records that are not "
                + "in the trash (BR-09).", example = "24.00")
        BigDecimal totalExpense,

        @Schema(description = "How many live records make up the total.", example = "3")
        Long transactionCount) {
}
