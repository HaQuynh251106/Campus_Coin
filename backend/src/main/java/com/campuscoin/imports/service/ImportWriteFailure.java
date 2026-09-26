package com.campuscoin.imports.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused an import write (UC-11).
 *
 * <p><b>The refusals this module has to tell apart are of two kinds, and the split is the whole reason
 * the class exists.</b>
 *
 * <ul>
 *   <li><b>A {@code SIGNAL} from {@code sp_apply_csv_batch}.</b> The procedure raises SQLSTATE
 *       {@code 45000} for two conditions, both of which mean the commit cannot proceed at all:
 *       {@code 'Import batch does not exist'} and
 *       {@code 'Import batch has already been processed or cancelled'}. The first is unreachable from
 *       any endpoint, because the service reads the batch through {@code ImportViewDao#findBatch}
 *       before it calls - which is an ownership-narrowed read, so a batch that is not the caller's
 *       produces "does not exist" and the call is never made. The second is reachable and ordinary: it
 *       is a batch that was committed or cancelled between the read and the call, or one the caller is
 *       asking to commit twice.</li>
 *   <li><b>An integrity constraint violation.</b> {@code uk_import_row} is the one this module can
 *       actually cause, and it can only fire if the same batch id were reused - the parser numbers a
 *       record once and the batch is created microseconds before its rows, so two rows of one batch
 *       cannot share a line number. It is classified anyway, because answering a unique-key collision
 *       as a server fault would hide a real fault behind a generic message.</li>
 * </ul>
 *
 * <p><b>Nothing is classified by message text.</b> The procedure's prose is not a contract and is
 * localised, and a test that pinned it would pin the wrong thing - the reasoning
 * {@code RecentActivityWriteFailure} and {@code BookmarkWriteFailure} both record. The SQLSTATE is
 * standard and the constraint name is the schema's own identifier, so both survive a locale change and
 * a driver upgrade.
 *
 * <p><b>Spring wraps a {@code SIGNAL} as an {@code InvalidDataAccessResourceUsageException}, not as a
 * {@code DataIntegrityViolationException}</b> - MySQL reports "resource usage" rather than an integrity
 * error for a user-raised state. That is why the check is on the SQLSTATE rather than on the exception
 * type, and why {@link #isSignalledRefusal} has to inspect the cause chain: the state lives on the
 * driver's own {@code SQLException}, under whichever wrapper the persistence layer chose.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is deliberately
 * not written to the log because its message carries the procedure's prose and the offending id.
 *
 * <p>Package-private and unit-tested, the shape {@code CategorisationWriteFailure} argues for: the
 * classification is the part that has to be right, and it cannot be exercised through the service,
 * whose own reads answer before this class is ever reached.
 */
final class ImportWriteFailure {

    /** The SQLSTATE MySQL raises for a procedure or trigger that refuses with {@code SIGNAL}. */
    private static final String SIGNAL_SQLSTATE = "45000";

    /** The SQLSTATE of an integrity constraint violation, including a unique key. */
    private static final String CONSTRAINT_SQLSTATE = "23000";

    /**
     * The unique key on {@code (batch_id, csv_row_no)}, matched by name so the check does not have to
     * treat every {@code 23000} as the same event - a foreign key violation is also {@code 23000} and
     * means something quite different.
     */
    private static final String IMPORT_ROW_UNIQUE_KEY = "uk_import_row";

    private ImportWriteFailure() {
    }

    /**
     * The commit procedure refused the batch outright.
     *
     * <p>Both conditions it signals collapse into one answer at the call site, and after the service's
     * own reads that is precisely right: the batch is not there to commit, or it is no longer open.
     * The caller's response to either is the same - reload the batch and look at its status - so
     * distinguishing them would be producing a distinction the client cannot use.
     */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * An integrity constraint refused a write.
     *
     * <p>Ordered after {@link #isSignalledRefusal} at the call site, because a procedure's {@code SIGNAL}
     * also arrives with a SQLSTATE and the ordering is what keeps each refusal reported as the rule
     * that actually fired - the reasoning {@code RecentActivityWriteFailure} records.
     */
    static boolean isConstraintViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                || hasSqlState(failure, CONSTRAINT_SQLSTATE);
    }

    /**
     * A row was refused by {@code uk_import_row}.
     *
     * <p>Separated from {@link #isConstraintViolation} because the two are answered differently, which
     * is the one thing this project asks of a classifier: {@code CategorisationWriteFailure} records
     * that where the constraint's name does not change the answer it is not matched, and where it does
     * it is. Here it does - a duplicate line number is a fault in this application's own numbering,
     * while a foreign key violation would mean the batch vanished mid-transaction - so the name is
     * matched.
     *
     * <p>Matched against the message because that is where MySQL puts the key's name: the exception
     * exposes a SQLSTATE and the server's text, and the text is the only carrier of which key fired.
     * This is the one place in this project that reads an error message, and it is safe for a reason
     * the others are not: the string is the schema's own identifier, fixed at table-creation time,
     * identical in every locale, and nothing else in the message is consulted. A production MySQL
     * emits no other wording for a duplicate key.
     */
    static boolean isImportRowCollision(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(IMPORT_ROW_UNIQUE_KEY)) {
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
