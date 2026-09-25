package com.campuscoin.recurring.service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused a recurring-rule write.
 *
 * <p>A separate type from {@link RecurringRuleService} for the reason
 * {@code TransactionWriteFailure} and {@code CategoryWriteFailure} give: the classification is the
 * part that has to be right, and inside the service it could not be tested directly. The rules are
 * asked about by SQLSTATE and by the constraint's own name, never by matching the driver's message
 * text - a message is not a contract and is localised, while the SQLSTATE is standard and a
 * constraint name is the schema's identifier.
 *
 * <p><b>One case here does read the message, and it is worth explaining why that is not a
 * contradiction.</b> {@code trg_recurring_rules_before_update} and
 * {@code trg_recurring_rules_before_insert} both call {@code sp_validate_recurring_rule}, which
 * raises the same {@code SQLSTATE '45000'} for four different rules. Three of them the service has
 * already checked for itself before the write - the category exists and is the caller's, the type
 * agrees, the rule is the caller's - so the only one that can still fire is the retired-category
 * refusal, and that is the one the service reports. The message is not read at all: the caller knows
 * which procedure it called a moment earlier, so it can attribute the signal without parsing text.
 * Only {@link #isSignalledRefusal} is needed, and it asks by SQLSTATE like every other check here.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is
 * deliberately not written to the log: a trigger's {@code SIGNAL} text names the rule and the table
 * it guards, and MySQL's constraint messages name the column and the offending value.
 */
final class RecurringRuleWriteFailure {

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

    private RecurringRuleWriteFailure() {
    }

    /** A trigger or stored procedure refused the write with {@code SIGNAL SQLSTATE '45000'}. */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * An integrity constraint violation: {@code ck_recurring_amount},
     * {@code ck_recurring_interval}, {@code ck_recurring_dates}, or a foreign key.
     *
     * <p>Checked after {@link #isSignalledRefusal}, because a trigger's {@code SIGNAL} also arrives
     * with a SQLSTATE.
     */
    static boolean isConstraintViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                || hasSqlState(failure, CONSTRAINT_SQLSTATE);
    }

    /**
     * Whether the failure mentions the retired-category rule.
     *
     * <p>Kept for one purpose only: distinguishing the retired-category refusal from the other three
     * {@code sp_validate_recurring_rule} signals when a caller wants to be certain which fired. The
     * service does not depend on it - it attributes the signal by knowing which checks it already
     * made - so this exists for the tests that verify the four rules are distinguishable at all.
     *
     * <p>It matches on a lowercase substring of the driver's message, which is the one place in the
     * codebase that inspects driver text. That is acceptable <em>here</em> and nowhere else because
     * nothing in the request path branches on it: a false negative costs nothing, since the caller
     * has already attributed the signal by SQLSTATE. It is deliberately not a
     * {@code Locale}-sensitive match - the message is a schema literal, not translated output.
     */
    static boolean mentionsRetiredCategory(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains("category has been disabled")) {
                return true;
            }
        }
        return false;
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
