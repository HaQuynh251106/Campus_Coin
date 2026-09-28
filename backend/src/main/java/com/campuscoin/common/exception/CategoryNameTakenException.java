package com.campuscoin.common.exception;

public class CategoryNameTakenException extends ApiException {

    public CategoryNameTakenException(String message) {
        super(ErrorCode.CATEGORY_NAME_TAKEN, message);
    }
}
