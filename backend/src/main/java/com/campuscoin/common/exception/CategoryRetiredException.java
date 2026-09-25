package com.campuscoin.common.exception;

/**
 * UC-09, BR-07: the rule's category has been retired, and the database therefore refuses every
 * change to the rule - including pausing or ending it.
 *
 * <p><b>This is a database behaviour the API cannot work around, so it reports it instead.</b>
 * {@code trg_recurring_rules_before_update} calls {@code sp_validate_recurring_rule} with the
 * rule's own values on every {@code UPDATE}, and that procedure refuses a disabled category
 * outright. There is no exemption for a status-only change, so a rule filed under a retired
 * category is frozen: it can be neither paused nor ended through the API. Retiring the category is
 * module 3's {@code PATCH /categories/{id}} with {@code isActive: false}, and the interaction is
 * not obvious when that toggle is flipped.
 *
 * <p>The remedy is to enable the category again, change the rule, and retire the category once
 * more - or to move the rule to another category while it is still enabled. A conflict rather than
 * a field error, because the caller sent nothing wrong: the rule is simply not modifiable in its
 * current state.
 */
public class CategoryRetiredException extends ApiException {

    public CategoryRetiredException(String message) {
        super(ErrorCode.CATEGORY_RETIRED, message);
    }
}
