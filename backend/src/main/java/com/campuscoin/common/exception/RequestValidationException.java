package com.campuscoin.common.exception;

import java.util.List;

public class RequestValidationException extends ApiException {

    private final transient List<ApiError.FieldError> fieldErrors;

    public RequestValidationException(String message, List<ApiError.FieldError> fieldErrors) {
        super(ErrorCode.VALIDATION_ERROR, message);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<ApiError.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
