package com.campuscoin.forecast.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a student's next month is expected to look like (UC-25).
 *
 * <p><b>Three blocks, and each one is a different kind of statement.</b> {@code currentMonth} reports
 * figures the database holds - what the student has actually recorded so far this month. {@code
 * projected} is an estimate from their recent complete months, and {@code basedOn} names exactly which
 * months it rests on. Keeping them apart is what makes the response honest: the section a student can
 * check against their own records and the section that is a guess are not blended into one number, and
 * the fields that say how thin the evidence is sit beside the estimate rather than being left for the
 * client to infer.
 *
 * <p><b>Either {@code currentMonth} or {@code projected} may be absent, and absence is meaningful.</b>
 * A student who has recorded nothing this month has no {@code currentMonth} block - a block of zeroes
 * would state that they had activity that netted to nothing. A student with no complete month behind
 * them has no {@code projected} block, because there is nothing to average; that is deliberately not
 * "0.00 projected", which would read as a confident prediction of no spending. The two absences are
 * independent: a new student has neither, a student in their first month has one and not the other.
 *
 * <p><b>{@code projectedSavings} can be negative.</b> It is projected income less projected expense,
 * and a negative value is the useful answer that spending is on course to outrun income. It is
 * published rather than floored at zero so the sign survives to the client.
 *
 * <p><b>{@code NON_NULL} is what turns "may be absent" into fact.</b> Every other component here is
 * a figure or a month label and is always present; only the two optional blocks are ever null. Including nulls
 * would emit {@code "currentMonthTotals": null}, which pushes the judgement onto the client: it
 * would have to know that a present-but-null block means "nothing recorded" rather than "the field
 * is not part of the contract". Omitting them makes the absence itself the statement, and it is the
 * convention every response DTO in this project follows - {@code TransactionResponse},
 * {@code BookmarkResponse}, {@code FlaggedTransactionResponse}.
 */
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

    /** UC-25: one complete month of the evidence the projection rests on. */
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

    /** UC-25: figures the database holds for the month in progress. */
    @Schema(description = "The month in progress, as recorded so far (UC-25).")
    public record CurrentMonthTotalsResponse(

            @Schema(description = "Total income recorded so far this month.", example = "260.00")
            BigDecimal income,

            @Schema(description = "Total spending recorded so far this month.", example = "120.00")
            BigDecimal expense,

            @Schema(description = "Income minus spending so far this month.", example = "140.00")
            BigDecimal net) {
    }

    /** UC-25: the estimate for the next month, and the net it implies. */
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
