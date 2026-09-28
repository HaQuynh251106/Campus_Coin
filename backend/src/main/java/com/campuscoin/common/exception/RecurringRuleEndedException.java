package com.campuscoin.common.exception;

public class RecurringRuleEndedException extends ApiException {

    public RecurringRuleEndedException(String message) {
        super(ErrorCode.RECURRING_RULE_ENDED, message);
    }
}
