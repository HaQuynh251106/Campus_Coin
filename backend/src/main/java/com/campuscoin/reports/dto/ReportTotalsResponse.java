package com.campuscoin.reports.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One month's totals for the signed-in student (UC-15).
 *
 * <p><b>Every figure is the database's answer, read from {@code v_monthly_income_expense}.</b> The
 * income/expense split comes from the category's type (BR-05), the totals count only records that are
 * not in the trash (BR-09), and {@code net} is the view's own subtraction rather than one made here.
 * The reports screen is where a student reconciles what they recorded against what they remember, so
 * a figure summed twice is a figure that will eventually disagree with the budget screen, the
 * dashboard or a transaction list.
 *
 * <p><b>All four fields are nullable, and their absence means the month itself is empty.</b>
 * {@code v_monthly_income_expense} returns one row per month that holds data, so a month with nothing
 * in it matches no row and there is no figure to report. A substituted zero would be a false
 * statement of a different kind - "you spent 0.00" asserts that the student's records were examined
 * and summed to nothing, where the truth is that there were no records. The month is still named, by
 * {@code ReportResponse.periodMonth}, so the client can render "no activity in July" rather than an
 * empty card. Contrast the trend points in the same response, which are exactly zero for a quiet
 * month, because BR-17 requires all six months and the view has already decided that zero is the
 * right answer there.
 *
 * <p>{@code net} is published although it is the view's subtraction of the other two, because it is
 * the view's quantity rather than a convenience: it is what BR-17's trend carries, and a client that
 * recomputed it would be the first step towards two versions of the same month's bottom line.
 * {@code transactionCount} is the number of live records behind the figures, so a student can tell
 * whether a total came from one large record or many small ones.
 */
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
