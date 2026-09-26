package com.campuscoin.anomaly.entity;

/**
 * The three values of {@code transactions.flag_type} (UC-24).
 *
 * <p>Mirrors the column's ENUM exactly. The constant names are the members the database stores, and
 * nothing else is accepted: a JSON number in their place is rejected, because a number would be an
 * ordinal whose meaning changes if these constants were ever reordered - the reasoning
 * {@code CategoryType}, {@code TransactionSource} and {@code RecentAction} all record.
 *
 * <p><b>{@code NONE} is a member of the enum and not an absence of one.</b> The column is
 * {@code NOT NULL DEFAULT 'NONE'}, so "this record is not flagged" has exactly one representation in
 * the database - {@code (is_flagged = 0, flag_type = 'NONE', flag_note = NULL)} - and the clearing
 * path writes that triple rather than deleting a row or leaving a stale note behind. Modelling it as
 * an enum member is what lets the detector's answer for every record be stated, including the answer
 * "nothing is wrong with this one", instead of being expressed as a null.
 *
 * <p><b>There is a third member beyond the two the feature looks for, and that is deliberate.</b>
 * {@code DUPLICATE} and {@code UNUSUAL_AMOUNT} are what the detector computes; {@code NONE} is what
 * it writes when a record no longer qualifies - after the student corrected it, or after the record
 * that made it a duplicate was trashed. A scan that could only ever set flags would leave a
 * corrected record marked forever, and the student's remedy for a flag is to correct the record.
 *
 * <p><b>No response of this module is typed by this enum as a client-supplied value.</b> It appears
 * in a response because the client has to render why a row is marked, and it appears nowhere in a
 * request: the type is what the detector concluded, and a client able to state it could mark its own
 * record as reviewed, which is the exact signal this feature exists to raise
 * ({@code docs/api/transactions.md}).
 */
public enum AnomalyFlagType {

    /** The record is not flagged. The stored form is {@code is_flagged = 0} with a null note. */
    NONE,

    /**
     * Same amount, same category, dated inside the configured window of another of the student's own
     * records - {@code anomaly.duplicate_window_days}.
     */
    DUPLICATE,

    /**
     * At least {@code anomaly.unusual_multiplier} times the student's own average for that category,
     * counting the rest of the category's records and not this one.
     */
    UNUSUAL_AMOUNT
}
