package com.campuscoin.common.exception;

public class TransactionStateException extends ApiException {

    private TransactionStateException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static TransactionStateException alreadyDeleted() {
        return new TransactionStateException(ErrorCode.TRANSACTION_ALREADY_DELETED,
                "This transaction has already been deleted.");
    }

    public static TransactionStateException notDeleted() {
        return new TransactionStateException(ErrorCode.TRANSACTION_NOT_DELETED,
                "This transaction is not deleted, so there is nothing to restore.");
    }
}
