package com.campuscoin.common.exception;

public class BookmarkAlreadyExistsException extends ApiException {

    public BookmarkAlreadyExistsException(String message) {
        super(ErrorCode.BOOKMARK_ALREADY_EXISTS, message);
    }
}
