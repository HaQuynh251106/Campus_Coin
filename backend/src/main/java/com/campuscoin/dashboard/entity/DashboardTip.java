package com.campuscoin.dashboard.entity;

import java.math.BigDecimal;

/**
 * One row of {@code v_dashboard_tips}: a tip to show on the dashboard, already filtered and ranked
 * (UC-12 B3).
 *
 * <p><b>The view does the filtering and the ranking, and both are load-bearing.</b>
 * {@code WHERE state <> 'DISMISSED'} is why a dismissed tip never comes back, and
 * {@code ORDER BY (state = 'PINNED') DESC, rank_score DESC} is why a pinned tip leads. Neither is
 * repeated here: re-sorting in Java would be a second definition of the order, and the one the view
 * computes is the one UC-18 will be judged against.
 *
 * <p><b>{@code periodMonth} is selected although the caller already knows which month it asked
 * for.</b> The view has no time filter - it returns every month a student has tips for - so the
 * month is what the query narrows on, and carrying it on the row means a tip can never be shown
 * under a month heading it does not belong to. It is a scope that was applied, recorded on the
 * result, rather than an assumption about what the query did.
 *
 * <p>{@code displayOrder} and {@code period_month} are deliberately not carried. The first is the
 * view's own ordering column and the order of the returned list is the same fact, so a second
 * expression of it would be a value to keep in step with the array. The second was used to narrow the
 * query and the response states the month once, so a copy on every row would be the same string
 * repeated.
 */
public record DashboardTip(
        Long tipId,
        Long categoryId,
        String title,
        String body,
        BigDecimal potentialSaving,
        TipState state) {
}
