package com.campuscoin.common.exception;

/**
 * Section 7.10: too many failed sign-in attempts or password reset requests.
 *
 * <p>Safeguards two abuses named in the requirement: brute-force login and excessive password
 * reset requests. The message deliberately says nothing about whether the account exists.
 */
public class TooManyAttemptsException extends ApiException {

    public TooManyAttemptsException(String message) {
        super(ErrorCode.TOO_MANY_ATTEMPTS, message);
    }
}
