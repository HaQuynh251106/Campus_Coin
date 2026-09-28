package com.campuscoin.reports.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One month's totals (UC-15). All four figures are absent when the month holds "
        + "no records at all.")
public record ReportTotalsResponse(

        @Schema(description = "Total income recorded that month, counting only records that are not "
                + "in the trash (BR-09). Absent when the month is empty.", example = "260.00",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal income,

        @Schema(description = "Total spending recorded that month, counting only records that are "
                + "not in the trash (BR-09). Absent when the month is empty.", example = "189.00",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal expense,

        @Schema(description = "Income minus spending, as the view computes it. Negative for a month "
                + "that spent more than it earned. Absent when the month is empty.", example = "71.00",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        BigDecimal net,

        @Schema(description = "How many live records the figures were computed from. Absent when the "
                + "month is empty.", example = "7", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long transactionCount) {
}
