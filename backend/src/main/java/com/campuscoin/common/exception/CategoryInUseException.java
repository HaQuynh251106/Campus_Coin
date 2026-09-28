package com.campuscoin.common.exception;

public class CategoryInUseException extends ApiException {

    public CategoryInUseException(String message) {
        super(ErrorCode.CATEGORY_IN_USE, message);
    }
}
