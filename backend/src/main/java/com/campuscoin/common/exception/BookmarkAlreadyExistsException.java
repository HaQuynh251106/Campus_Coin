package com.campuscoin.common.exception;

/**
 * UC-19 B1: the caller has already saved this item, so there is nothing to bookmark.
 *
 * <p>The unique key {@code uk_bookmark_dedupe} is what actually prevents a second row - its
 * {@code dedupe_key} is {@code user_id|item_type|tip_id}, which is "one student saves one item once"
 * stated exactly. This exception exists so the collision is reported as something the caller can act on
 * rather than as a generic refused write: bookmarking something twice is not a mistake worth a
 * database error, it is a student who already has what they asked for.
 *
 * <p>A conflict rather than a field error, because no field the caller sent is wrong. The item
 * identifier and the item type are both valid; it is their combination, with the caller's own existing
 * row, that collides. The status is {@code 409} and the code is distinct from
 * {@link DataConflictException}'s, so a client can say "already in your list" and offer to open it,
 * which is the difference between a useful message and a dead end. The shape is deliberately the same
 * as {@link BudgetAlreadyExistsException}, which answers the same kind of collision for UC-13.
 *
 * <p>Deliberately <em>not</em> an idempotent success. Returning the existing bookmark with a
 * {@code 201} would tell the caller a row was created when none was, and would silently discard the
 * note they sent - the caller would believe their note was saved when it was not. Reporting the
 * conflict leaves the remedy with the caller, and PATCH is where the note is changed.
 */
public class BookmarkAlreadyExistsException extends ApiException {

    public BookmarkAlreadyExistsException(String message) {
        super(ErrorCode.BOOKMARK_ALREADY_EXISTS, message);
    }
}
