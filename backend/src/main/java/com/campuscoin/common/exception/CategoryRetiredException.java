package com.campuscoin.common.exception;

public class CategoryRetiredException extends ApiException {

    public CategoryRetiredException(String message) {
        super(ErrorCode.CATEGORY_RETIRED, message);
    }
}
