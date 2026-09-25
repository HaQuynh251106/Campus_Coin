package com.campuscoin.reports.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One bar of a spending series: a day, or an ISO week (UC-15).
 *
 * <p><b>One shape for both granularities.</b> {@code intervalStart} and {@code intervalEnd} are the
 * same day for a daily point and the Monday and Sunday for a weekly one, so a client draws one chart
 * and widens the bars, rather than branching on two payloads that differ only in how much time a bar
 * covers. The response states which of the two it is, so the client never has to infer it from
 * whether the two dates differ.
 *
 * <p><b>{@code totalExpense} is positive.</b> {@code ck_txn_amount} requires {@code amount > 0}, so a
 * record's direction is its category's type (BR-05) and never a sign on the number;
 * {@code v_daily_spending_current_month} filters to expense categories and sums the magnitudes, so
 * this is an amount spent rather than a signed movement. It is passed through untransformed so that
 * this report and a transaction list agree about the same record.
 *
 * <p><b>{@code intervalEnd} is read from the view rather than computed here.</b> It is the view that
 * defined Sunday as the last day of an ISO week - {@code DATE_ADD(MIN(week_start), INTERVAL 6 DAY)} -
 * and a client should be told the boundary the database used, not one derived in Java that could
 * disagree with it across a daylight-saving rule or a locale.
 *
 * <p>{@code transactionCount} is how many live records make up the bar, so a spike made of one large
 * purchase is distinguishable from one made of several.
 */
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
