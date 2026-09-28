package com.campuscoin.common.exception;

public class TooManyAttemptsException extends ApiException {

    public TooManyAttemptsException(String message) {
        super(ErrorCode.TOO_MANY_ATTEMPTS, message);
    }
}
