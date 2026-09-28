package com.campuscoin.common.exception;

public class AccountDisabledException extends ApiException {

    public AccountDisabledException() {
        super(ErrorCode.ACCOUNT_DISABLED,
                "This account is currently disabled. Please contact the administrator.");
    }
}
