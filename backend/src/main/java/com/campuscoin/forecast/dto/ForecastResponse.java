package com.campuscoin.forecast.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A student's current-month totals and a projection for the next month (UC-25).")
public record ForecastResponse(

        @Schema(description = "The month the projection is for, as `yyyy-MM` - the month after the "
                + "one in progress.", example = "2026-10")
        String nextMonth,

        @Schema(description = "The month in progress, as `yyyy-MM`.", example = "2026-09")
        String currentMonth,

        @Schema(description = "How many complete months the projection averaged over. `0` when "
                + "there is no projection yet.", example = "3")
        int basedOnMonths,

        @Schema(description = "The student's recent complete months, oldest first. The evidence the "
                + "projection rests on, so a client can show or check it. Empty for a student with no "
                + "complete month behind them.")
        List<ForecastMonthResponse> recentMonths,

        @Schema(description = "What the student has recorded so far in the month in progress. "
                + "Omitted when nothing is recorded yet.")
        CurrentMonthTotalsResponse currentMonthTotals,

        @Schema(description = "The projection for the next month. Omitted when there is no complete "
                + "month to average.")
        ProjectedMonthResponse projected) {

    @Schema(description = "One complete month the projection averaged over (UC-25).")
    public record ForecastMonthResponse(

            @Schema(description = "The month, as `yyyy-MM`.", example = "2026-08")
            String periodMonth,

            @Schema(description = "Total income recorded that month.", example = "260.00")
            BigDecimal income,

            @Schema(description = "Total spending recorded that month.", example = "189.00")
            BigDecimal expense,

            @Schema(description = "Income minus spending that month.", example = "71.00")
            BigDecimal net) {
    }

    @Schema(description = "The month in progress, as recorded so far (UC-25).")
    public record CurrentMonthTotalsResponse(

            @Schema(description = "Total income recorded so far this month.", example = "260.00")
            BigDecimal income,

            @Schema(description = "Total spending recorded so far this month.", example = "120.00")
            BigDecimal expense,

            @Schema(description = "Income minus spending so far this month.", example = "140.00")
            BigDecimal net) {
    }

    @Schema(description = "The projection for the next month (UC-25).")
    public record ProjectedMonthResponse(

            @Schema(description = "Projected total income, the average of the recent months.",
                    example = "260.00")
            BigDecimal income,

            @Schema(description = "Projected total spending, the average of the recent months.",
                    example = "189.00")
            BigDecimal expense,

            @Schema(description = "Projected income minus projected spending. Negative when "
                    + "spending is on course to outrun income.", example = "71.00")
            BigDecimal savings) {
    }
}
