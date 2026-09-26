package com.campuscoin.categorisation.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused a UC-08 write.
 *
 * <p>Three different rules can refuse the statements this module runs, and they arrive wearing
 * different SQLSTATEs that this project must not confuse with each other.
 *
 * <ul>
 *   <li>{@code trg_transactions_before_update} calls {@code sp_validate_transaction}, which raises
 *       BR-13 - "suggested category belongs to another student" - and BR-08 when the row's date is in
 *       the future. Both are {@code SIGNAL SQLSTATE '45000'}.</li>
 *   <li>{@code fk_txn_ai_category} refuses a suggested category that does not exist,
 *       {@code ck_txn_ai_conf} refuses a confidence outside {@code [0,1]}, and
 *       {@code fk_rule_category} refuses a rule naming a category that is not there. These are
 *       constraint violations, SQLSTATE {@code 23000}.</li>
 * </ul>
 *
 * <p><b>What each of them means to the API.</b> BR-13 is the only one this module can cause by
 * mistake, and the answer is that the suggestion is dropped rather than the request failing: a
 * suggestion is advisory by BR-13 and the student's own filing is untouched, so a proposal the
 * database will not accept is not a reason to refuse the filing. The others are application faults -
 * the suggester resolves every category against {@code CategoryRepository#findVisibleToUser} before
 * writing, so a category that does not exist cannot reach the statement, and the confidence is a
 * scaled value from a provider or the certain value for a rule - so they are rethrown and answered as
 * an internal error rather than hidden.
 *
 * <p><b>Rules are asked about by SQLSTATE and by exception type, never by message text.</b> The
 * SQLSTATE is standard, and MySQL's own message for a {@code SIGNAL} carries the procedure's prose and
 * the offending identifier - neither of which is part of any contract.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is deliberately
 * not written to the log because its message names the offending id.
 *
 * <p>Package-private and unit-tested, the shape {@code AnomalyWriteFailure} argues for: the
 * classification is the part that has to be right, and it is not reachable through the service, whose
 * own reads have already narrowed to the caller's rows and the caller's own categories before either
 * statement runs.
 */
final class CategorisationWriteFailure {

    /**
     * The SQLSTATE MySQL raises for a procedure or trigger that refuses with {@code SIGNAL}.
     *
     * <p>Spring does not turn this into a {@code DataIntegrityViolationException}: it arrives as an
     * {@code InvalidDataAccessResourceUsageException}, because MySQL reports "resource usage" rather
     * than an integrity error. Asking by SQLSTATE rather than by exception type is what makes the
     * refusal recognisable whichever wrapper the persistence layer chose - the reasoning
     * {@code AnomalyWriteFailure.SIGNAL_SQLSTATE} records for the same state.
     */
    private static final String SIGNAL_SQLSTATE = "45000";

    /** The SQLSTATE of an integrity constraint violation, including a restricting foreign key. */
    private static final String CONSTRAINT_SQLSTATE = "23000";

    private CategorisationWriteFailure() {
    }

    /**
     * A trigger or procedure refused with {@code SIGNAL SQLSTATE '45000'}.
     *
     * <p>Against the {@code transactions} update this is BR-13 or BR-08, and both mean the same thing to
     * this module: the database would not accept the advisory note. Against
     * {@code CategoryRuleDao#upsert} no {@code SIGNAL} is raised at all - nothing signals on
     * {@code category_rules} - so an occurrence there is a foreign key failing under a
     * {@code 23000} first.
     */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * An integrity constraint violation: a foreign key or a {@code CHECK} the statement violated.
     *
     * <p>Ordered after {@link #isSignalledRefusal} at the call site, because a procedure's {@code SIGNAL}
     * also carries a SQLSTATE and the ordering is what keeps each refusal reported as the rule that
     * actually fired. The two do not overlap here - {@code 45000} and {@code 23000} are distinct - but
     * the order is kept because the more specific refusal is the one worth naming.
     *
     * <p>Deliberately not narrowed by constraint name. There is one answer to give - "the database did
     * not accept this write" - so distinguishing {@code fk_txn_ai_category} from {@code fk_rule_category}
     * would not change the response. The name would only add a way to be wrong: an unrelated failure
     * whose message text happened to mention a constraint would be classified as one. Where the name
     * does change the answer, {@code BookmarkWriteFailure} matches it; here it does not, so it does not.
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
