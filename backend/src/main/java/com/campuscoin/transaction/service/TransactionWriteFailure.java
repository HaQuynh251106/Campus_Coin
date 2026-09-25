package com.campuscoin.transaction.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused a transaction write.
 *
 * <p>A separate type from {@link TransactionService} for the reason
 * {@code CategoryWriteFailure} gives: the classification is the part that has to be right, and in
 * the service it could not be tested directly. {@code sp_validate_transaction} signals
 * {@code SQLSTATE '45000'} for six different rules with six different messages, and the API can only
 * distinguish them by how the service called it - so the branch that matters is the one that
 * recognises a signal at all, and that is verified here against the exact exception shapes the
 * driver produces.
 *
 * <p>The rules are asked about by SQLSTATE and by the constraint's own name, never by the driver's
 * message text. A message is not a contract and is localised; the SQLSTATE is standard, and a
 * constraint name is the schema's identifier and appears untranslated in every locale. The message
 * is what must not be trusted <em>and</em> must not be forwarded - see
 * {@link TransactionService#translateWriteFailure}.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is
 * deliberately not written to the log: a trigger's {@code SIGNAL} message names the rule it guards,
 * and MySQL's constraint messages name the table, the column and the offending value.
 */
final class TransactionWriteFailure {

    /**
     * The SQLSTATE MySQL raises for a trigger or procedure that refuses with {@code SIGNAL}.
     *
     * <p>Spring does not translate this into a {@code DataIntegrityViolationException}: it arrives as
     * {@code InvalidDataAccessResourceUsageException}, because MySQL reports "resource usage" rather
     * than an integrity error. Asking by SQLSTATE rather than by exception type is what makes the
     * refusal recognisable whichever wrapper the persistence layer chose.
     */
    private static final String SIGNAL_SQLSTATE = "45000";

    /** The SQLSTATE of an integrity constraint violation, including a CHECK or a foreign key. */
    private static final String CONSTRAINT_SQLSTATE = "23000";

    private TransactionWriteFailure() {
    }

    /** A trigger or stored procedure refused the write with {@code SIGNAL SQLSTATE '45000'}. */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * An integrity constraint violation: {@code ck_txn_amount}, {@code ck_txn_deleted}, a foreign
     * key, or a column length.
     *
     * <p>Checked after {@link #isSignalledRefusal}, because a trigger's {@code SIGNAL} also arrives
     * with a SQLSTATE. Ordering is what keeps each refusal reported as the rule that actually fired.
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
