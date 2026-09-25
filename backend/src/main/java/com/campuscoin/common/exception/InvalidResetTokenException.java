package com.campuscoin.common.exception;

/**
 * BR-04 / UC-03 A1: the password reset token is invalid, already used, or expired.
 *
 * <p>Raised when the database refuses the reset. {@code sp_complete_password_reset} signals
 * SQLSTATE 45000 for exactly this case, so the database stays the single authority on whether
 * a token is still usable.
 */
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
