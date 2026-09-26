package com.campuscoin.anomaly.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused an anomaly-flag write (UC-24).
 *
 * <p>{@code sp_flag_transaction} raises SQLSTATE {@code 45000} for three different reasons:
 *
 * <ul>
 *   <li>"Invalid anomaly flag type" - a value outside {@code NONE}/{@code DUPLICATE}/
 *       {@code UNUSUAL_AMOUNT};</li>
 *   <li>"Transaction does not exist" - the id matches no row;</li>
 *   <li>"BR-02: cannot flag a transaction owned by another student" - the row exists and is somebody
 *       else's.</li>
 * </ul>
 *
 * <p>They need different answers. The first is a request that could be corrected; the other two are
 * both "there is no such transaction of yours" and must be <em>indistinguishable</em> from the
 * outside, because telling them apart would let a caller learn which transaction identifiers exist
 * (section 7.5). The procedure's prose is not a contract and is not read here. Instead the first is
 * eliminated before any SQL runs - {@link com.campuscoin.anomaly.entity.AnomalyFlagType} is a Java
 * enum and the detector only ever produces one of its members, so an invalid type cannot reach the
 * procedure through this API at all - and the remaining {@code 45000} is answered as one thing: not
 * yours, or not there. The procedure keeps its own check, which is what holds for a hand-run
 * {@code CALL}.
 *
 * <p>That is the same division of labour {@code RecentActivityWriteFailure} records for the trigger
 * it cannot tell apart from a missing row, and {@code TransactionWriteFailure} for its own.
 *
 * <p><b>Rules are asked about by SQLSTATE and by constraint name, never by message text.</b> The
 * SQLSTATE is standard; {@code fk_txn_user} is the schema's identifier and appears untranslated in
 * every locale.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is deliberately
 * not written to the log because its message carries the procedure's prose and the offending
 * identifier.
 *
 * <p>Package-private and unit-tested, the shape {@code RecentActivityWriteFailure} argues for: the
 * classification is the part that has to be right, and it cannot be reached through the service,
 * whose own query has already narrowed to the caller's records before the procedure is called.
 */
final class AnomalyWriteFailure {

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

    private AnomalyWriteFailure() {
    }

    /**
     * The procedure refused with {@code SIGNAL SQLSTATE '45000'}.
     *
     * <p>After the enum narrowing above, this means one of two things and they are answered
     * identically: the transaction does not exist, or it belongs to another student. Deliberately not
     * split - see the class note.
     */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * An integrity constraint violation: the transaction's user or category foreign key.
     *
     * <p>Ordered after {@link #isSignalledRefusal} at the call site, because a procedure's SIGNAL also
     * arrives with a SQLSTATE and the ordering is what keeps each refusal reported as the rule that
     * actually fired.
     *
     * <p>The foreign key reachable here is {@code fk_txn_user}, and only in principle rather than in
     * practice: the procedure checks the row exists and is the caller's before it updates, so an id
     * that matches nothing else's records is refused by the {@code SIGNAL} branch first. It fires if
     * the row is hard-deleted between the procedure's {@code SELECT} and its {@code UPDATE} -
     * possible, since {@code fk_txn_user} is {@code ON DELETE CASCADE} while a student's own delete is
     * a soft one. Classified so that if it ever happens it is answered as "no such transaction" rather
     * than as a server fault.
     *
     * <p><b>Decided by SQLSTATE and by exception type, and deliberately not by the constraint's
     * name.</b> There is only one answer to give - "no such transaction of yours" - so distinguishing
     * which constraint fired would not change the response. The name would only add a way to be wrong:
     * an unrelated failure whose text happened to mention {@code fk_txn_user} would be classified as a
     * constraint violation and reported as a missing row. Where the name does change the answer,
     * {@code BookmarkWriteFailure} matches it; here it does not, so it does not.
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
