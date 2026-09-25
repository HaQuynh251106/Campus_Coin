package com.campuscoin.common.exception;

/**
 * UC-13, BR-11: a limit already exists for this category and month, and there may be at most one.
 *
 * <p>The unique key {@code uk_budget_user_cat_month} - {@code (user_id, category_id, period_month)},
 * which is BR-11 stated exactly - is what actually prevents a second row. This exception exists so
 * the collision is reported as something the caller can act on rather than as a generic refused
 * write: setting a limit twice is not a mistake worth a database error, it is a student changing
 * their mind, and the message says so by naming the operation that does that.
 *
 * <p>A conflict rather than a field error, because no field the caller sent is wrong. The category
 * and the month are both valid; it is their combination, with the caller's own existing row, that
 * collides.
 *
 * <p>The status is {@code 409} and the code is distinct from {@link DataConflictException}'s, so a
 * client can offer "update the existing limit" instead of "try again", which is the difference
 * between a useful message and a dead end.
 */
public class BudgetAlreadyExistsException extends ApiException {

    public BudgetAlreadyExistsException(String message) {
        super(ErrorCode.BUDGET_ALREADY_EXISTS, message);
    }
}
