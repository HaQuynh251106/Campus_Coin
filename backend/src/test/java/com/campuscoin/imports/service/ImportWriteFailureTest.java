package com.campuscoin.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class ImportWriteFailureTest {

    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    private static SQLException duplicateRowNumber() {
        return new SQLException(
                "Duplicate entry '12-7' for key 'import_rows.uk_import_row'", "23000", 1062);
    }

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("Import batch has already been processed or cancelled"));

        assertThat(ImportWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(ImportWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Both conditions the procedure signals classify identically")
    void everySignalClassifiesTheSameWay() {

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

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("nested", duplicateRowNumber()));

        assertThat(ImportWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("A duplicate row number is recognised by the key's name")
    void theImportRowCollisionIsRecognised() {

        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                duplicateRowNumber());

        assertThat(ImportWriteFailure.isImportRowCollision(failure)).isTrue();
    }

    @Test
    @DisplayName("A constraint that is not the row key is not the row key")
    void anotherConstraintIsNotTheRowKey() {

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
