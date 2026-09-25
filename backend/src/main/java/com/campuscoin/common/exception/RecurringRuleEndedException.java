package com.campuscoin.common.exception;

/**
 * UC-09: the rule has already ended, and {@code ENDED} is terminal - an ended rule cannot be
 * changed back.
 *
 * <p><b>Why this is the application's rule and not the database's.</b>
 * {@code recurring_rules.status} is an ENUM and accepts any of the three values at any time;
 * nothing in the schema forbids a row moving from {@code ENDED} back to {@code ACTIVE}. The
 * restriction is stated by the requirement's state machine instead - {@code ACTIVE} and
 * {@code PAUSED} may move between each other, either may be ended, and {@code ENDED} is final. It
 * is enforced here, in the service, because a status is an application-state decision and the
 * database deliberately has no opinion about it.
 *
 * <p><b>Why it matters rather than being pedantic.</b> Un-ending a rule is the one change that
 * could contradict the transactions already posted. Ending is what the module offers instead of
 * deleting a rule that has posted (see {@code RecurringRuleService#delete}), and the student's
 * understanding is that the schedule is over: the history is theirs to read, and the rule will
 * not post again. A rule that could be revived would make "ended" mean "paused until someone
 * flips it", and a revived rule whose cursor is in the past would immediately generate
 * transactions dated to the period it was ended for - the opposite of what ending it said.
 *
 * <p>The remedy is a new rule, which is also the honest description of what the student wants: a
 * fresh schedule with its own start date rather than an old one resumed mid-thought.
 *
 * <p>A conflict rather than a field error: the value sent is a valid status, so no field is
 * wrong - it simply does not apply to this rule's current state.
 */
public class RecurringRuleEndedException extends ApiException {

    public RecurringRuleEndedException(String message) {
        super(ErrorCode.RECURRING_RULE_ENDED, message);
    }
}
