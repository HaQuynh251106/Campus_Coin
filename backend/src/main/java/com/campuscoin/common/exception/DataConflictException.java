package com.campuscoin.common.exception;

/**
 * A database rule refused a write and the caller's remedy is to reload and retry. UC-07, UC-10.
 *
 * <p>This is the answer for a refusal that is not about a field the caller sent. The transaction
 * endpoints pre-check everything the client can see - that the record is the caller's, that the
 * category is usable and active, that the date is not in the future, that the state transition
 * applies - so a refusal that still reaches the database layer means one of those facts changed
 * between the check and the write: the category was deleted or retired, or the record was deleted,
 * by another request, or the day rolled over mid-request.
 *
 * <p>Naming a field in that situation would be guessing which of those it was, and would point the
 * client at an input that is in fact correct. The status says the request conflicted with the
 * database's current state; the message says what to do about it, which is reload.
 *
 * <p>The database's own text is never forwarded. A trigger's {@code SIGNAL} message names the rule
 * and the table, and MySQL's constraint messages name the column and the offending value - all
 * internal identifiers, and none of them part of the API contract.
 */
public class DataConflictException extends ApiException {

    public DataConflictException(String message) {
        super(ErrorCode.DATA_CONFLICT, message);
    }
}
