package com.campuscoin.recurring.entity;

/**
 * The three values of {@code recurring_rules.status}.
 *
 * <p>{@code ACTIVE} is the only status {@code sp_post_recurring_transactions} selects, so
 * {@code PAUSED} is what stops a rule generating transactions without losing either its definition
 * or the transactions it already generated.
 *
 * <p>{@code ENDED} is reached by two different actors, and the distinction is the reason the value
 * matters here:
 *
 * <ul>
 *   <li><b>The procedure</b>, when its cursor walks past {@code end_date}: the rule has run out on
 *       its own.</li>
 *   <li><b>A student</b>, through the update endpoint, when they want the rule to stop for good.
 *       This is also the module's answer to "remove a rule that has already posted something", which
 *       cannot be a real delete - the transactions it generated point back at it through
 *       {@code recurring_rule_id}, and removing the row would leave those pointers dangling. See
 *       {@code RecurringRuleService#delete} for what was verified about that.</li>
 * </ul>
 *
 * <p><b>The lifecycle these three values describe.</b> {@code ACTIVE} and {@code PAUSED} are the
 * two live states and a rule may move between them freely - pausing is meant to be reversible, and
 * it is what a student does to a schedule they will want again. Either live state may be ended.
 * {@code ENDED} is final: the requirement's state machine does not admit a move out of it, and
 * {@code RecurringRuleService#update} refuses one as {@code 409 RECURRING_RULE_ENDED} rather than
 * letting a revived rule post transactions for the period it was ended for.
 *
 * <p>That restriction is the application's, not the schema's. The column is an ENUM and would
 * accept any of the three values at any time; the allowed transitions are enforced in the service
 * because a status is an application-state decision and the database has no opinion about it.
 */
public enum RecurringStatus {
    ACTIVE,
    PAUSED,
    ENDED
}
