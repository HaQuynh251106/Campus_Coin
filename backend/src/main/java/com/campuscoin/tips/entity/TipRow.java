package com.campuscoin.tips.entity;

import java.math.BigDecimal;

/**
 * One row of {@code v_dashboard_tips}: a tip to show, already filtered and ranked (UC-18).
 *
 * <p><b>The view does the filtering and the ranking, and both are load-bearing.</b>
 * {@code WHERE state <> 'DISMISSED'} is why a dismissed tip never comes back, and
 * {@code ORDER BY (state = 'PINNED') DESC, rank_score DESC} is why a pinned tip leads. Neither is
 * repeated here: re-sorting in Java would be a second definition of the order, and the one the view
 * computes is the one BR-14 is judged against.
 *
 * <p><b>{@code displayOrder} and {@code periodMonth} are deliberately not carried.</b> The first is
 * the view's own ordering column, and the order of the returned list is the same fact, so a second
 * expression of it would be a value to keep in step with the array. The second was used to narrow
 * the query, and the response states the month once, so a copy on every row would be the same string
 * repeated - the reasoning {@code DashboardTip} records for the same two columns.
 *
 * <p><b>{@code rankScore} is not carried either.</b> It is what the view ranks by, so publishing it
 * would be a second way to ask for an order the array already expresses; the tip the student sees
 * second is second.
 *
 * <p>This is a projection rather than a managed entity: the query returns columns a view computed,
 * and nothing here is written back through it.
 */
public record TipRow(
        Long tipId,
        Long categoryId,
        String title,
        String body,
        BigDecimal potentialSaving,
        TipState state) {
}
