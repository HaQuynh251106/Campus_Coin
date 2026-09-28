package com.campuscoin.common.exception;

public class EmailAlreadyRegisteredException extends ApiException {

    public EmailAlreadyRegisteredException() {
        super(ErrorCode.EMAIL_ALREADY_REGISTERED,
                "This email address is already registered. Please sign in or reset your password.");
    }
}
