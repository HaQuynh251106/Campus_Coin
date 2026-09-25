package com.campuscoin.dashboard.entity;

/**
 * The states {@code v_dashboard_tips} can return for a tip (UC-12 B3).
 *
 * <p>Only two members, because the view's {@code WHERE state <> 'DISMISSED'} removes the third before
 * this enum is ever consulted - the same restriction {@code ConsumptionStatus} carries, and for the
 * same reason: a member that cannot arrive would be a branch no client could ever take.
 * {@code user_tips.state} does have a {@code DISMISSED} value; it belongs to UC-18, which is where a
 * tip is dismissed, and a dismissed tip never appears on a dashboard again.
 *
 * <p>The name is parsed with {@code valueOf} rather than matched loosely, so a column value this enum
 * does not know about fails loudly at the read instead of quietly reaching a client that would render
 * nothing.
 *
 * <p><b>Managing these two states is not this module's.</b> Pinning and dismissing are UC-18
 * operations with their own endpoints in a later module; the dashboard only reports what the view
 * ordered, and pinning is honoured in the ordering rather than by any flag this module sets.
 */
public enum TipState {

    /** Generated and not yet acted on. */
    NEW,

    /** Pinned by the student, so the view sorts it ahead of the unpinned tips. */
    PINNED
}
