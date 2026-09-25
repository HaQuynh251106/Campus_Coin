package com.campuscoin.tips.entity;

/**
 * The three states {@code user_tips.state} can hold (UC-18).
 *
 * <p><b>All three members, unlike the dashboard's view of the same column.</b> The dashboard
 * publishes only {@link #NEW} and {@link #PINNED}, because {@code v_dashboard_tips} removes a
 * dismissed tip before the value is ever read - a dismissed tip is not something a dashboard shows.
 * This module is where a tip <em>becomes</em> dismissed, so it has to name the state it is moving a
 * tip into, and unmapping it here would make the transition this module exists for unexpressible.
 *
 * <p><b>Each member is paired with a timestamp, and the pairing is a database constraint.</b>
 * {@code ck_tip_state} requires {@code PINNED} to carry a {@code pinned_at}, {@code DISMISSED} to
 * carry a {@code dismissed_at}, and {@code NEW} to carry neither. The check is why a state change
 * here is a two-field write rather than a flag flip: setting {@code state} alone would leave the row
 * violating the constraint, and the database would refuse it - correctly, because a pinned tip with
 * no time it was pinned is a fact the schema declines to store.
 *
 * <p>The name is read and written by name, never by ordinal, so a column value this enum does not
 * know about fails at the read instead of silently shifting meaning to the next member.
 */
public enum TipState {

    /** Generated and not yet acted on. Carries neither timestamp. */
    NEW,

    /** Pinned by the student, so {@code v_dashboard_tips} sorts it ahead of the unpinned tips. */
    PINNED,

    /** Dismissed by the student, so {@code v_dashboard_tips} excludes it for good. */
    DISMISSED
}
