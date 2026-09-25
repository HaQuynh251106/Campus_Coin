package com.campuscoin.common.exception;

/**
 * UC-02 A1: authentication failed.
 *
 * <p>The message is deliberately identical whether the email is unknown or the password is
 * wrong. Telling the caller which of the two was incorrect would turn the login endpoint into
 * an account-enumeration oracle.
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(ErrorCode.INVALID_CREDENTIALS, "Incorrect email or password.");
    }
}
