package com.campuscoin.budget.entity;

/**
 * The kinds of notification a student can receive - the {@code notifications.type} ENUM, verbatim.
 *
 * <p>All seven values are mapped even though only two are produced by a module that exists today.
 * The column is a MySQL {@code ENUM}, so an unmapped member would be a value this application
 * cannot deserialise: a row written by the CSV import, by {@code sp_generate_tips} or by an
 * administrator announcement would fail to load and take the whole notification list down with it,
 * rather than simply appearing as an unfamiliar kind. Mapping the complete set costs nothing and
 * makes the read path total.
 *
 * <p>The two that matter for UC-14 are {@link #BUDGET_NEAR} and {@link #BUDGET_EXCEEDED}. They are
 * written by {@code sp_check_budget_alerts}, which is called from the transaction triggers - never
 * by this module. See {@code BudgetService} for why the API does not raise them itself.
 */
public enum NotificationType {

    /** UC-14, BR-12: spending reached {@code budget.near_threshold_pct} of a category's limit. */
    BUDGET_NEAR,

    /** UC-14, BR-12: spending reached {@code budget.exceeded_threshold_pct} of a limit. */
    BUDGET_EXCEEDED,

    /** UC-21: a system-wide announcement an administrator published. Module 11. */
    ANNOUNCEMENT,

    /** A message the platform needs the student to see, written outside any single use case. */
    SYSTEM,

    /** UC-17: a monthly insight has been generated. Module 12. */
    INSIGHT_READY,

    /** UC-18: a saving tip was generated. Module 9. */
    TIP,

    /** UC-09: the scheduler posted a recurring rule's transaction. Module 5's scheduler. */
    RECURRING_POSTED
}
