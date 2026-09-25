package com.campuscoin.common.exception;

/**
 * Base class for every failure the application raises deliberately.
 *
 * <p>Only {@link #getMessage()} reaches the client. Causes are kept for logging and
 * are never serialised into a response (section 7.7: no stack traces, SQL statements,
 * credentials or internal implementation details).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
