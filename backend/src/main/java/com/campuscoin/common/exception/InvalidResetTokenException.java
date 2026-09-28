package com.campuscoin.common.exception;

public class InvalidResetTokenException extends ApiException {

    public InvalidResetTokenException() {
        super(ErrorCode.INVALID_RESET_TOKEN,
                "This password reset link is invalid or has expired. Please request a new one.");
    }

    public InvalidResetTokenException(Throwable cause) {
        super(ErrorCode.INVALID_RESET_TOKEN,
                "This password reset link is invalid or has expired. Please request a new one.",
                cause);
    }
}
