package com.campuscoin.common.exception;

public class DataConflictException extends ApiException {

    public DataConflictException(String message) {
        super(ErrorCode.DATA_CONFLICT, message);
    }
}
