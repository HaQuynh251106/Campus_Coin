package com.campuscoin.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),

    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),

    INVALID_REQUEST(HttpStatus.BAD_REQUEST),

    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),

    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED),

    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS),

    ACCESS_DENIED(HttpStatus.FORBIDDEN),

    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),

    CATEGORY_NAME_TAKEN(HttpStatus.CONFLICT),

    CATEGORY_IN_USE(HttpStatus.CONFLICT),

    TRANSACTION_ALREADY_DELETED(HttpStatus.CONFLICT),

    TRANSACTION_NOT_DELETED(HttpStatus.CONFLICT),

    RECURRING_RULE_IN_USE(HttpStatus.CONFLICT),

    CATEGORY_RETIRED(HttpStatus.CONFLICT),

    RECURRING_RULE_ENDED(HttpStatus.CONFLICT),

    BUDGET_ALREADY_EXISTS(HttpStatus.CONFLICT),

    BOOKMARK_ALREADY_EXISTS(HttpStatus.CONFLICT),

    SELF_DISABLE_FORBIDDEN(HttpStatus.CONFLICT),

    THRESHOLD_NOT_ADJUSTABLE(HttpStatus.CONFLICT),

    TIP_TEMPLATE_CODE_TAKEN(HttpStatus.CONFLICT),

    TIP_TEMPLATE_CODE_IMMUTABLE(HttpStatus.CONFLICT),

    NOT_FOUND(HttpStatus.NOT_FOUND),

    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

    DATA_CONFLICT(HttpStatus.CONFLICT),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
