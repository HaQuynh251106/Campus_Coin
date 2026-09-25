package com.campuscoin.budget.entity;

/**
 * How much of a monthly limit has been used - the classification {@code v_budget_consumption}
 * derives.
 *
 * <p><b>This is not a column.</b> {@code budgets} stores a limit and nothing else; this value is
 * the view's {@code CASE} over {@code spent_amount / limit_amount}, and it is the same comparison
 * {@code sp_check_budget_alerts} makes when it decides whether to raise an alert. Both read the
 * thresholds from {@code system_settings} - {@code budget.near_threshold_pct} (80 by default) and
 * {@code budget.exceeded_threshold_pct} (100) - so a student who changes the threshold changes the
 * label and the alert together, from one setting, with no redeploy (VĐ-05).
 *
 * <p><b>Kept as an enum rather than a bare string</b> so the API publishes a fixed set of values. A
 * client switching on the label gets a compile-time-checked set, and a typo in the view would fail
 * to map loudly rather than reaching a frontend that silently matched nothing.
 *
 * <p>The three values do not line up with the two alert thresholds one-for-one, and that is the
 * database's design rather than an omission: a student can see {@code NEAR} at 80% long before an
 * alert is stored, because the alert is a message that must not repeat (BR-12) while this label is
 * recomputed on every read. {@code ON_TRACK} is everything below the near threshold, including a
 * month with no spending at all.
 */
public enum ConsumptionStatus {

    /** Below {@code budget.near_threshold_pct}: no action needed. */
    ON_TRACK,

    /** At or above {@code budget.near_threshold_pct}: approaching the limit. */
    NEAR,

    /** At or above {@code budget.exceeded_threshold_pct}: the limit is used up or passed. */
    EXCEEDED
}
