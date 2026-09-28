package com.campuscoin.common.exception;

public class SelfDisableForbiddenException extends ApiException {

    public SelfDisableForbiddenException(String message) {
        super(ErrorCode.SELF_DISABLE_FORBIDDEN, message);
    }
}
