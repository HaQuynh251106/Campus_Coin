package com.campuscoin.category.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused a category write.
 *
 * <p>A separate type from {@link CategoryService} for one reason: the classification is the part
 * that has to be right, and in the service it could not be tested directly. Two of the three
 * refusals are reachable through the API and are covered there, but the unique-key branch is not -
 * the service checks the name before inserting, so it fires only when two requests interleave
 * between the check and the insert. A concurrency test can show that the end state is correct
 * without ever entering this branch. Keeping the classification here lets it be verified on its
 * own, with the exact exceptions the driver produces, instead of being taken on trust.
 *
 * <p>The rules are asked about by SQLSTATE and by the constraint's own name, never by the driver's
 * message text. A message is not a contract and is localised; the SQLSTATE is standard, and
 * {@code uk_categories_scope_type_name} is the schema's identifier and appears untranslated in
 * every locale. The message is what must not be trusted <em>and</em> must not be forwarded - see
 * {@link CategoryService#translateWriteFailure}.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is
 * deliberately not written to the log because its message carries the constraint name and the
 * scope key.
 */
final class CategoryWriteFailure {

    /**
     * The SQLSTATE MySQL raises for a trigger or procedure that refuses with {@code SIGNAL}.
     *
     * <p>Spring does not translate this into a {@code DataIntegrityViolationException}: it arrives
     * as {@code InvalidDataAccessResourceUsageException}, because MySQL reports "resource usage"
     * rather than an integrity error. Asking by SQLSTATE rather than by exception type is what
     * makes the refusal recognisable whichever wrapper the persistence layer chose.
     */
    private static final String SIGNAL_SQLSTATE = "45000";

    /** The SQLSTATE of an integrity constraint violation, including a restricting foreign key. */
    private static final String CONSTRAINT_SQLSTATE = "23000";

    /** The unique key on {@code (scope_key, type, name)} that keeps a student's names distinct. */
    private static final String UNIQUE_NAME_CONSTRAINT = "uk_categories_scope_type_name";

    private CategoryWriteFailure() {
    }

    /**
     * The unique key on {@code (scope_key, type, name)} refused the write.
     *
     * <p>Recognised by the constraint name rather than by SQLSTATE alone, because SQLSTATE 23000
     * covers every integrity violation and the restricting foreign keys raise it too. The name also
     * has to be present: a {@code DataIntegrityViolationException} from a check constraint or a
     * foreign key is not a name clash, and reporting it as one would tell the caller to pick a
     * different name when the name was never the problem.
     *
     * <p>This branch is reachable only when a competing request commits between this request's
     * pre-check and its insert, which is exactly the window the pre-check cannot close.
     */
    static boolean isUniqueNameViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                && mentions(failure, UNIQUE_NAME_CONSTRAINT);
    }

    /** A trigger or stored procedure refused the write with {@code SIGNAL SQLSTATE '45000'}. */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * An integrity constraint violation: a restricting foreign key, or a CHECK.
     *
     * <p>Checked after the two above, because a trigger's SIGNAL also arrives with a SQLSTATE and
     * the unique key also arrives as a {@code DataIntegrityViolationException}. Ordering is what
     * keeps each refusal reported as the rule that actually fired.
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
