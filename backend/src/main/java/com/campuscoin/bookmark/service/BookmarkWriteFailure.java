package com.campuscoin.bookmark.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused a bookmark write.
 *
 * <p>A separate type from {@link BookmarkService} for the reason {@code CategoryWriteFailure} and
 * {@code TransactionWriteFailure} are: the classification is the part that has to be right, and in the
 * service it could not be tested directly. The duplicate-key branch is the one that matters here - the
 * service checks for an existing bookmark before inserting, so that branch fires only when two requests
 * interleave between the check and the insert, which a concurrency test can show the end state of
 * without ever entering. Keeping the classification here lets it be verified on its own, with the exact
 * exceptions the driver produces, instead of being taken on trust.
 *
 * <p><b>The rules are asked about by SQLSTATE and by the constraint's own name, never by the driver's
 * message text.</b> A message is not a contract and is localised; the SQLSTATE is standard, and
 * {@code uk_bookmark_dedupe} is the schema's identifier and appears untranslated in every locale. The
 * {@code SIGNAL} text of {@code trg_bookmarks_before_insert} is deliberately not matched either - the
 * trigger and this class are two halves of one project, but the trigger can be reworded without the API
 * contract changing, and a classifier that keyed on its prose would break silently when it was.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is deliberately
 * not written to the log because its message carries the constraint name and the offending key.
 */
final class BookmarkWriteFailure {

    /**
     * The SQLSTATE MySQL raises for a trigger or procedure that refuses with {@code SIGNAL}.
     *
     * <p>Spring does not translate this into a {@code DataIntegrityViolationException}: it arrives as
     * {@code InvalidDataAccessResourceUsageException}, because MySQL reports "resource usage" rather
     * than an integrity error. Asking by SQLSTATE rather than by exception type is what makes the
     * refusal recognisable whichever wrapper the persistence layer chose.
     */
    private static final String SIGNAL_SQLSTATE = "45000";

    /** The SQLSTATE of an integrity constraint violation, including a restricting foreign key. */
    private static final String CONSTRAINT_SQLSTATE = "23000";

    /**
     * The unique key on {@code dedupe_key} that keeps one student from saving an item twice.
     *
     * <p>What this branch means differs from the category module's equivalent in one way worth stating:
     * there the collision is with a name the caller chose, here the caller has not chosen anything that
     * could collide. Both are the same rule from the caller's side - the item is already in the list -
     * so both are answered with a conflict that says so.
     */
    private static final String UNIQUE_DEDUPE_CONSTRAINT = "uk_bookmark_dedupe";

    private BookmarkWriteFailure() {
    }

    /**
     * The unique key on {@code dedupe_key} refused the insert: the caller already saved this item.
     *
     * <p>Recognised by the constraint name rather than by SQLSTATE alone, because SQLSTATE 23000 covers
     * every integrity violation - and here it also covers the foreign keys, which raise it too. The name
     * has to be present: a violation from {@code fk_bookmark_tip} is "there is no such tip", not "you
     * already saved it", and reporting it as a duplicate would tell the caller to look in a list that
     * does not contain the item.
     *
     * <p>Reachable when a competing request commits between this request's pre-check and its insert,
     * which is exactly the window the pre-check cannot close.
     */
    static boolean isDuplicateBookmark(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                && mentions(failure, UNIQUE_DEDUPE_CONSTRAINT);
    }

    /**
     * A trigger refused the write with {@code SIGNAL SQLSTATE '45000'}.
     *
     * <p>For this table that is BR-02: {@code trg_bookmarks_before_insert} and
     * {@code trg_bookmarks_before_update} refuse a target the caller does not own, so one student
     * cannot save - and thereby read - another student's tip. It reaches this classifier only if the
     * service's own check was passed and the row's owner changed in between, or if a note edit was
     * attempted on a row whose target is not the caller's, which the update trigger catches.
     */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * An integrity constraint violation: a foreign key, or the {@code ck_bookmark_target} CHECK.
     *
     * <p>Checked after the two above, because a trigger's SIGNAL also arrives with a SQLSTATE and the
     * unique key also arrives as a {@code DataIntegrityViolationException}. Ordering is what keeps each
     * refusal reported as the rule that actually fired.
     *
     * <p>The foreign key reaching here means the tip named does not exist, and
     * {@code ck_bookmark_target} means the target columns disagree with {@code item_type} - neither of
     * which this module can produce, since {@code Bookmark.newTipBookmark} writes both together. They
     * are classified so that if one ever fires it is answered as a conflict rather than as a server
     * error.
     */
    static boolean isConstraintViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                || hasSqlState(failure, CONSTRAINT_SQLSTATE);
    }

    /** Walks the cause chain: the SQLSTATE is on the driver's exception, not on Spring's wrapper. */
    private static boolean hasSqlState(Throwable failure, String sqlState) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && Objects.equals(sqlState, sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentions(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
