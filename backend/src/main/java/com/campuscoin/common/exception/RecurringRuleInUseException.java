package com.campuscoin.common.exception;

public class RecurringRuleInUseException extends ApiException {

    public RecurringRuleInUseException(String message) {
        super(ErrorCode.RECURRING_RULE_IN_USE, message);
    }
}
