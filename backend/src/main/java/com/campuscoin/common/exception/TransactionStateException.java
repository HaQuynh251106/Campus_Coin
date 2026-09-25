package com.campuscoin.common.exception;

/**
 * A transaction state change that does not apply to the record's current state (UC-10, BR-09).
 *
 * <p>Two situations share this type because they are one condition from the caller's point of view
 * - the record is not in the state the operation starts from. Deleting a record that is already in
 * the trash and restoring one that was never deleted are both "somebody already did this, or the
 * client's copy is stale", and the client's remedy is the same: reload and look.
 *
 * <p>Each carries its own {@link ErrorCode} so the client can tell which direction the mismatch
 * went, which is the same split {@code CATEGORY_NAME_TAKEN} and {@code CATEGORY_IN_USE} use.
 */
public class TransactionStateException extends ApiException {

    private TransactionStateException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /** UC-10: {@code sp_soft_delete_transaction} refuses a row that is already deleted. */
    public static TransactionStateException alreadyDeleted() {
        return new TransactionStateException(ErrorCode.TRANSACTION_ALREADY_DELETED,
                "This transaction has already been deleted.");
    }

    /** UC-10 A1: restore was asked for a record that is not in the trash. */
    public static TransactionStateException notDeleted() {
        return new TransactionStateException(ErrorCode.TRANSACTION_NOT_DELETED,
                "This transaction is not deleted, so there is nothing to restore.");
    }
}
