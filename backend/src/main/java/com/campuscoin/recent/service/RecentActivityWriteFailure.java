package com.campuscoin.recent.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused a recent-activity write (UC-26).
 *
 * <p>This module has the simplest write in the API and is nevertheless where the classifier matters
 * most, because <b>every</b> refusal here arrives as SQLSTATE {@code 45000} and the procedure raises
 * that state for three different reasons. {@code sp_touch_recent_activity} signals:
 *
 * <ul>
 *   <li>"Invalid recent-activity action" - a value outside {@code VIEWED}/{@code EDITED};</li>
 *   <li>"Transaction does not exist" - the id matches no row;</li>
 *   <li>"BR-02: cannot record activity for a transaction owned by another student" - the row exists
 *       and belongs to somebody else.</li>
 * </ul>
 *
 * <p>The three need different answers: the first is a request the caller can correct, the other two
 * are both "there is no such transaction of yours" and must be <em>indistinguishable</em> from the
 * outside, because telling them apart would let a caller learn which transaction identifiers exist
 * (section 7.5). The procedure's prose is not a contract and is localised, so it is not read here.
 * Instead the first is eliminated before any SQL runs - {@code action} is a Java enum that Jackson
 * has already parsed, so a third value is a Bean Validation failure on the request field and never
 * reaches the procedure at all - and the remaining {@code 45000} is answered as one thing: not yours,
 * or not there. The procedure keeps its own check, which is what holds for a hand-run {@code CALL}.
 *
 * <p>That is the same division of labour {@code BookmarkWriteFailure} records for the trigger it
 * cannot tell apart from a missing row, and {@code TransactionWriteFailure} for its own.
 *
 * <p><b>Rules are asked about by SQLSTATE and by constraint name, never by message text.</b> The
 * SQLSTATE is standard; {@code fk_recent_txn} and {@code uk_recent} are the schema's identifiers and
 * appear untranslated in every locale.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is deliberately
 * not written to the log because its message carries the procedure's prose and the offending key.
 *
 * <p>Package-private and unit-tested, the shape {@code BookmarkWriteFailure} argues for: the
 * classification is the part that has to be right and it cannot be tested through the service, whose
 * ownership check answers before this class is ever reached.
 */
final class RecentActivityWriteFailure {

    /**
     * The SQLSTATE MySQL raises for a procedure or trigger that refuses with {@code SIGNAL}.
     *
     * <p>Spring does not turn this into a {@code DataIntegrityViolationException}: it arrives as an
     * {@code InvalidDataAccessResourceUsageException}, because MySQL reports "resource usage" rather
     * than an integrity error. Asking by SQLSTATE rather than by exception type is what makes the
     * refusal recognisable whichever wrapper the persistence layer chose - the reasoning
     * {@code BookmarkWriteFailure.SIGNAL_SQLSTATE} records for the same state.
     */
    private static final String SIGNAL_SQLSTATE = "45000";

    /** The SQLSTATE of an integrity constraint violation, including a restricting foreign key. */
    private static final String CONSTRAINT_SQLSTATE = "23000";

    private RecentActivityWriteFailure() {
    }

    /**
     * The procedure refused with {@code SIGNAL SQLSTATE '45000'}.
     *
     * <p>After the enum parsing and Bean Validation above it, this means one of two things and they
     * are answered identically: the transaction does not exist, or it belongs to another student.
     * Deliberately not split - see the class note.
     */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * An integrity constraint violation: the transaction foreign key, or the {@code uk_recent} upsert.
     *
     * <p>Ordered after {@link #isSignalledRefusal} at the call site, because a procedure's SIGNAL also
     * arrives with a SQLSTATE and the ordering is what keeps each refusal reported as the rule that
     * actually fired.
     *
     * <p>The foreign key reachable here is {@code fk_recent_txn}, and only in principle rather than in
     * practice: the procedure checks the row exists before it inserts, so an id that matches nothing
     * is refused by the {@code SIGNAL} branch first. It fires if the transaction is hard-deleted
     * between the procedure's {@code SELECT} and its {@code INSERT} - possible, since
     * {@code fk_recent_txn} is {@code ON DELETE CASCADE} while a student's own delete is a soft one.
     * Classified so that if it ever happens it is answered as "no such transaction" rather than as a
     * server fault.
     *
     * <p>{@code uk_recent} is likewise not a refusal here: the procedure does not insert blindly into
     * the unique key, it upserts with {@code ON DUPLICATE KEY UPDATE occurred_at = NOW()}, so the
     * second view of the same transaction is an update rather than a collision. Recognising the
     * SQLSTATE for it keeps that true if the procedure is ever changed to a plain {@code INSERT} - the
     * record exists, and re-viewing it is not a conflict the student could act on.
     *
     * <p><b>Decided by SQLSTATE and by exception type, and deliberately not by the constraint's name
     * this time.</b> There is only one answer to give - "no such transaction of yours" - so
     * distinguishing which constraint fired would not change the response. The name would only add a
     * way to be wrong: an unrelated failure whose text happened to mention {@code fk_recent_txn} would
     * be classified as a constraint violation and reported as a missing row. Where the name does
     * change the answer, {@code BookmarkWriteFailure} matches it; here it does not, so it does not.
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
}
