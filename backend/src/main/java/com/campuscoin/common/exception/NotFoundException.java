package com.campuscoin.common.exception;

/**
 * The addressed resource does not exist, or does not exist for this caller.
 *
 * <p>The message is deliberately about the caller's own request rather than the database: a
 * response that distinguished "no such row" from "not yours" would let a client probe for
 * identifiers that belong to other accounts (section 7.5).
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
