package com.campuscoin.common.exception;

/**
 * UC-01 A1 / UAT-01: the email address is already registered, so no account is created.
 * BR-01 defines the login email as unique.
 */
public class EmailAlreadyRegisteredException extends ApiException {

    public EmailAlreadyRegisteredException() {
        super(ErrorCode.EMAIL_ALREADY_REGISTERED,
                "This email address is already registered. Please sign in or reset your password.");
    }
}
