package com.campuscoin.common.exception;

/**
 * UC-02 A3 / E1: the request carries no usable authentication.
 *
 * <p>Raised when an access token is missing, malformed, mis-signed, expired, revoked, or was
 * issued before the account's {@code token_version} changed. All of those are one condition from
 * the client's point of view - sign in again - so they share a single error code and message and
 * cannot be told apart from the outside.
 */
public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException(String message) {
        super(ErrorCode.UNAUTHENTICATED, message);
    }
}
