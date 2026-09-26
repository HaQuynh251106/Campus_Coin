package com.campuscoin.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification for UC-11's statements, tested directly.
 *
 * <p><b>Why this is a unit test.</b> Both recognisers take a {@code Throwable} and answer a boolean, so
 * nothing about them needs a database and every case is expressible exactly as MySQL and Spring produce
 * it. It also cannot be exercised through the service: {@code ImportService} reads the batch through an
 * ownership-narrowed query before it calls the procedure, so the "batch does not exist" refusal is
 * unreachable from any endpoint and the classifier is only reached by the concurrent-modification case.
 *
 * <p><b>Why a classifier at all.</b> A commit can be refused two quite different ways - the procedure
 * signalling that the batch is no longer open, and a unique key refusing a row - and both must be
 * answered without forwarding a database message. Recognising them by SQLSTATE and by the constraint's
 * name, never by prose, is what keeps that answer stable across a locale change and a driver upgrade.
 *
 * <p><b>The nesting matters as much as the SQLSTATE.</b> Spring wraps the driver's exception, sometimes
 * more than once, and the state is only on the innermost one. A test that passed a bare
 * {@code SQLException} would prove nothing about how the exceptions actually arrive.
 *
 * <p><b>What must <em>not</em> be swept in is asserted last.</b> A lost connection, a deadlock and a
 * plain application error all mean something other than "the database refused this write", and
 * classifying any of them as a conflict would turn "try again" into a message about the student's data.
 */
class ImportWriteFailureTest {

    /** What {@code SIGNAL SQLSTATE '45000'} becomes: no error number, and not an integrity error. */
    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    /** A duplicate row number as MySQL reports it: 1062, SQLSTATE 23000, the key named. */
    private static SQLException duplicateRowNumber() {
        return new SQLException(
                "Duplicate entry '12-7' for key 'import_rows.uk_import_row'", "23000", 1062);
    }

    // ------------------------------------------------------------------
    //  A refusal from the procedure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {
        // InvalidDataAccessResourceUsageException -> SQLException 45000, which is how MySQL's SIGNAL
        // arrives: Spring reports "resource usage" rather than an integrity error.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("Import batch has already been processed or cancelled"));

        assertThat(ImportWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(ImportWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Both conditions the procedure signals classify identically")
    void everySignalClassifiesTheSameWay() {
        // "does not exist" and "already processed" reach the caller as one answer - reload the batch -
        // so distinguishing them would be producing a distinction the client cannot use. That
        // equivalence is also why nothing depends on the procedure's wording.
        for (String message : new String[]{
                "Import batch does not exist",
                "Import batch has already been processed or cancelled"}) {

            Throwable failure = new InvalidDataAccessResourceUsageException(
                    "could not execute statement", signalledRefusal(message));

            assertThat(ImportWriteFailure.isSignalledRefusal(failure))
                    .as("message=%s", message)
                    .isTrue();
            assertThat(ImportWriteFailure.isConstraintViolation(failure))
                    .as("message=%s", message)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("The refusal is recognised when its message is not the procedure's current text")
    void rewordingDoesNotBreakTheClassification() {
        // The procedure's prose is not a contract: it can be reworded without the API's behaviour
        // changing, so nothing may depend on the words.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("some future wording the SQL was never asked about"));

        assertThat(ImportWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("The SQLSTATE is found even when the driver's exception is nested deeper")
    void nestingDoesNotHideTheSqlState() {
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("wrapped once",
                        signalledRefusal("Import batch does not exist")));

        assertThat(ImportWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  Integrity violations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A constraint violation is recognised as one")
    void aConstraintViolationIsRecognised() {
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                duplicateRowNumber());

        assertThat(ImportWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(ImportWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    @Test
    @DisplayName("The SQLSTATE qualifies a constraint even under an unrelated wrapper type")
    void theSqlStateIsEnoughWithoutTheType() {
        // The persistence layer's choice of wrapper is not a contract either. Deciding by the standard
        // SQLSTATE is what makes the recognition survive a different exception type from the same driver.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("nested", duplicateRowNumber()));

        assertThat(ImportWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  The unique key, which is answered differently from any other constraint
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A duplicate row number is recognised by the key's name")
    void theImportRowCollisionIsRecognised() {
        // This is the one place in the project that reads an error message, and the reason is that the
        // key's name is the schema's own identifier: fixed at table-creation time, identical in every
        // locale, and the only carrier of which constraint fired. It is answered differently from the
        // other constraints because a duplicate line number is a fault in this application's numbering,
        // while a foreign key violation would mean the batch vanished mid-transaction.
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                duplicateRowNumber());

        assertThat(ImportWriteFailure.isImportRowCollision(failure)).isTrue();
    }

    @Test
    @DisplayName("A constraint that is not the row key is not the row key")
    void anotherConstraintIsNotTheRowKey() {
        // fk_import_row_batch and fk_import_row_category are on the same table and are flatly different
        // events from a duplicate line number, so they must not answer as one.
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                new SQLException(
                        "Cannot add or update a child row: a foreign key constraint fails "
                                + "(`campuscoin`.`import_rows`, CONSTRAINT `fk_import_row_batch` "
                                + "FOREIGN KEY (`batch_id`) REFERENCES `import_batches` (`id`))",
                        "23000", 1452));

        assertThat(ImportWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(ImportWriteFailure.isImportRowCollision(failure)).isFalse();
    }

    @Test
    @DisplayName("The key's name is found even when the exception is nested deeper")
    void theKeyNameIsFoundThroughTheChain() {
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("wrapped once", duplicateRowNumber()));

        assertThat(ImportWriteFailure.isImportRowCollision(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  What must not be mislabelled
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The two recognisers are distinguishable, so the order at the call site decides")
    void theRecognisersAreDistinguishable() {
        Throwable signalled = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("Import batch does not exist"));
        Throwable constraint = new DataIntegrityViolationException("could not execute statement",
                duplicateRowNumber());

        assertThat(ImportWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(ImportWriteFailure.isConstraintViolation(signalled)).isFalse();
        assertThat(ImportWriteFailure.isSignalledRefusal(constraint)).isFalse();
        assertThat(ImportWriteFailure.isConstraintViolation(constraint)).isTrue();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreLeftAlone() {
        // A lost connection and a deadlock are the database being unavailable, not the database
        // refusing. Sweeping either in would answer "reload the batch" for a transient fault - and for a
        // deadlock, which is exactly what a retry fixes, that would be the worst possible answer.
        Throwable connectionLost = new DataAccessResourceFailureException("Communications link failure",
                new SQLException("Communications link failure", "08S01", 0));
        Throwable deadlock = new CannotAcquireLockException("Deadlock found when trying to get lock",
                new SQLException("Deadlock found", "40001", 1213));
        Throwable plain = new IllegalStateException("no sql at all");

        for (Throwable failure : new Throwable[]{connectionLost, deadlock, plain}) {
            assertThat(ImportWriteFailure.isSignalledRefusal(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
            assertThat(ImportWriteFailure.isConstraintViolation(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
            assertThat(ImportWriteFailure.isImportRowCollision(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A log-shaped string mentioning the key is not a collision")
    void aMessageAboutTheKeyIsNotAViolation() {
        // The name appearing in prose is not the database raising anything. A classifier keyed on the
        // substring alone would answer "duplicate line number" - and so "a fault in this application" -
        // for a plain error whose text happened to mention the key.
        Throwable proseAboutTheKey = new IllegalStateException(
                "the row was rejected by uk_import_row, see the docs");

        assertThat(ImportWriteFailure.isConstraintViolation(proseAboutTheKey)).isFalse();
        assertThat(ImportWriteFailure.isSignalledRefusal(proseAboutTheKey)).isFalse();
    }

    @Test
    @DisplayName("A null message or a null-ed chain does not break the classification")
    void nullMessagesAndCausesAreHandled() {
        Throwable nullMessage = new InvalidDataAccessResourceUsageException("could not execute",
                new SQLException((String) null, "23000", 1452));
        Throwable noCause = new InvalidDataAccessResourceUsageException("no cause");

        assertThat(ImportWriteFailure.isSignalledRefusal(nullMessage)).isFalse();
        assertThat(ImportWriteFailure.isConstraintViolation(nullMessage)).isTrue();
        assertThat(ImportWriteFailure.isImportRowCollision(nullMessage)).isFalse();

        assertThat(ImportWriteFailure.isSignalledRefusal(noCause)).isFalse();
        assertThat(ImportWriteFailure.isConstraintViolation(noCause)).isFalse();
        assertThat(ImportWriteFailure.isImportRowCollision(noCause)).isFalse();
    }
}
