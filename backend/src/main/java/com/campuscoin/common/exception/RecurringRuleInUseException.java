package com.campuscoin.common.exception;

/**
 * UC-09: the rule has already generated transactions, so removing it would break them.
 *
 * <p>Deleting a rule that has posted nothing is ordinary housekeeping, and this module allows it.
 * Deleting one that has posted something is a different operation, and it is refused here for a
 * reason that is not obvious from the schema: {@code transactions.recurring_rule_id} carries no
 * foreign key (by design - see {@code docs/DB_DESIGN.md} section 4.8), so a delete would not cascade
 * and would not be refused by the database either. It would simply leave those transactions
 * pointing at a row that no longer exists.
 *
 * <p>That is worse than a dangling pointer, because {@code sp_validate_transaction} re-checks the
 * reference on every write. A transaction whose {@code recurring_rule_id} names a missing rule
 * cannot be edited, soft-deleted or restored again - each of those raises
 * {@code BR-02: recurring rule does not exist}. The damage would not stay inside this module: it
 * would break the transaction module's edit, delete and restore endpoints for records that were
 * working before.
 *
 * <p>So the answer is not to delete but to end: {@code status = ENDED} stops the rule for good while
 * leaving the transactions it generated intact and editable. The message says so, because a client
 * that only sees "conflict" would have no way to know that is what is being asked of it.
 */
public class RecurringRuleInUseException extends ApiException {

    public RecurringRuleInUseException(String message) {
        super(ErrorCode.RECURRING_RULE_IN_USE, message);
    }
}
