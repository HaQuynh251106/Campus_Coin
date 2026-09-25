package com.campuscoin.budget.service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused a budget write (UC-13).
 *
 * <p>A separate type from {@code BudgetService} for the reason the other modules' equivalent types
 * give: the classification is the part that has to be right, and inside the service it could not be
 * tested directly. The rules are asked about by SQLSTATE and by the constraint's own name, never by
 * matching the driver's message text - a message is not a contract and is localised, while the
 * SQLSTATE is standard and a constraint name is the schema's identifier.
 *
 * <p><b>One case reads the message, and it is worth explaining why that is not a contradiction.</b>
 * {@code trg_budgets_before_insert} and {@code trg_budgets_before_update} both call
 * {@code sp_validate_budget}, which raises the same {@code SQLSTATE '45000'} for four different
 * rules. Three of them the service has already checked for itself before the write - the category
 * exists and is the caller's, it is an expense category, it is active - so the one that can still
 * fire is a category retired or deleted by a concurrent request. The message is not parsed to decide
 * the answer; the service attributes the signal by knowing which checks it already made. Only
 * {@link #isSignalledRefusal} is needed for that, and it asks by SQLSTATE like every other check
 * here.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is deliberately
 * not written to the log: a trigger's {@code SIGNAL} text names the rule and the table it guards, and
 * MySQL's constraint messages name the column and the offending value.
 */
final class BudgetWriteFailure {

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

    /** The unique key BR-11 is stated as: one limit per student, category and month. */
    private static final String BUDGET_UNIQUE_KEY = "uk_budget_user_cat_month";

    private BudgetWriteFailure() {
    }

    /** A trigger or stored procedure refused the write with {@code SIGNAL SQLSTATE '45000'}. */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * An integrity constraint violation: {@code ck_budget_limit}, {@code ck_budget_month},
     * {@code uk_budget_user_cat_month}, or a foreign key.
     *
     * <p>Checked after {@link #isSignalledRefusal}, because a trigger's {@code SIGNAL} also arrives
     * with a SQLSTATE.
     */
    static boolean isConstraintViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                || hasSqlState(failure, CONSTRAINT_SQLSTATE);
    }

    /**
     * Whether the failure is the one-limit-per-month unique key.
     *
     * <p>The one place in this class that reads the driver's message, and it is a constraint
     * <em>name</em> being looked for, not a sentence: {@code uk_budget_user_cat_month} is the
     * schema's own identifier for BR-11. That is a weaker kind of text-matching than parsing prose,
     * and it is safe here because {@link com.campuscoin.budget.service.BudgetService} looks the
     * collision up before the write on every path it can, so reaching this branch means two requests
     * for the same new limit raced - a case the service could not have detected and which the
     * unique key, correctly, refused.
     *
     * <p>It matches case-insensitively because MySQL reports identifiers in the case the statement
     * used, and it is deliberately not a {@code Locale}-sensitive match - the identifier is a schema
     * literal, not translated output. A false negative costs nothing: the write is still refused and
     * reported as a generic conflict rather than mislabelled as a success.
     */
    static boolean mentionsDuplicateLimit(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains(BUDGET_UNIQUE_KEY)) {
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
