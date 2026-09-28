package com.campuscoin.common.exception;

public class BudgetAlreadyExistsException extends ApiException {

    public BudgetAlreadyExistsException(String message) {
        super(ErrorCode.BUDGET_ALREADY_EXISTS, message);
    }
}
