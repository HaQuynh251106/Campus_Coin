package com.campuscoin.categorisation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class CategorisationWriteFailureTest {

    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    private static SQLException missingCategoryForeignKey(String constraint) {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`transactions`, CONSTRAINT `" + constraint + "` FOREIGN KEY "
                        + "(`ai_suggested_category_id`) REFERENCES `categories` (`id`))",
                "23000", 1452);
    }

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-13: suggested category belongs to another student"));

        assertThat(CategorisationWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(CategorisationWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Both rules the validation can raise are recognised the same way")
    void everyRefusalIsRecognised() {

        for (String message : new String[]{
                "BR-13: suggested category belongs to another student",
                "BR-08: transaction date cannot be in the future"}) {

            Throwable failure = new InvalidDataAccessResourceUsageException(
                    "could not execute statement", signalledRefusal(message));

            assertThat(CategorisationWriteFailure.isSignalledRefusal(failure))
                    .as("message=%s", message)
                    .isTrue();
            assertThat(CategorisationWriteFailure.isConstraintViolation(failure))
                    .as("message=%s", message)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("The refusal is recognised when its message is not the procedure's current text")
    void rewordingDoesNotBreakTheClassification() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("some future wording the SQL was never asked about"));

        assertThat(CategorisationWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("The SQLSTATE is found even when the driver's exception is nested deeper")
    void nestingDoesNotHideTheSqlState() {
        SQLException inner = signalledRefusal("BR-13: suggested category belongs to another student");
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("wrapped once", inner));

        assertThat(CategorisationWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("A foreign key violation is recognised as a constraint violation")
    void foreignKeyViolationIsARecognisedConstraint() {
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                missingCategoryForeignKey("fk_txn_ai_category"));

        assertThat(CategorisationWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(CategorisationWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    @Test
    @DisplayName("Every constraint either statement can hit is swept into one answer")
    void everyConstraintIsSweptInDeliberately() {

        Throwable unknownCategory = new DataIntegrityViolationException("could not execute statement",
                missingCategoryForeignKey("fk_txn_ai_category"));
        Throwable outOfRangeConfidence = new DataIntegrityViolationException("could not execute statement",
                new SQLException("Check constraint 'ck_txn_ai_conf' is violated.", "23000", 3819));
        Throwable foreignRule = new DataIntegrityViolationException("could not execute statement",
                new SQLException(
                        "Cannot add or update a child row: a foreign key constraint fails "
                                + "(`campuscoin`.`category_rules`, CONSTRAINT `fk_rule_category`)",
                        "23000", 1452));

        for (Throwable failure : new Throwable[]{unknownCategory, outOfRangeConfidence, foreignRule}) {
            assertThat(CategorisationWriteFailure.isConstraintViolation(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isTrue();
            assertThat(CategorisationWriteFailure.isSignalledRefusal(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("The SQLSTATE is enough on its own, whichever SQLException carries it")
    void theSqlStateIsEnoughWithoutTheType() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("nested", missingCategoryForeignKey("fk_rule_category")));

        assertThat(CategorisationWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("The two recognisers are distinguishable, so the order at the call site decides")
    void theRecognisersAreDistinguishable() {
        Throwable signalled = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-08: transaction date cannot be in the future"));
        Throwable constraint = new DataIntegrityViolationException("could not execute statement",
                missingCategoryForeignKey("fk_txn_ai_category"));

        assertThat(CategorisationWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(CategorisationWriteFailure.isConstraintViolation(signalled)).isFalse();
        assertThat(CategorisationWriteFailure.isSignalledRefusal(constraint)).isFalse();
        assertThat(CategorisationWriteFailure.isConstraintViolation(constraint)).isTrue();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreLeftAlone() {

        Throwable connectionLost = new DataAccessResourceFailureException(
                "Communications link failure",
                new SQLException("Communications link failure", "08S01", 0));
        Throwable deadlock = new org.springframework.dao.CannotAcquireLockException(
                "Deadlock found when trying to get lock",
                new SQLException("Deadlock found", "40001", 1213));
        Throwable plain = new IllegalStateException("no sql at all");

        for (Throwable failure : new Throwable[]{connectionLost, deadlock, plain}) {
            assertThat(CategorisationWriteFailure.isSignalledRefusal(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
            assertThat(CategorisationWriteFailure.isConstraintViolation(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A log-shaped string mentioning a constraint name is not a violation")
    void aMessageAboutTheConstraintIsNotAViolation() {

        Throwable proseAboutTheKey = new IllegalStateException(
                "the write was rejected by fk_txn_ai_category, see the docs");

        assertThat(CategorisationWriteFailure.isConstraintViolation(proseAboutTheKey)).isFalse();
        assertThat(CategorisationWriteFailure.isSignalledRefusal(proseAboutTheKey)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreHandled() {
        Throwable withNullMessage = new InvalidDataAccessResourceUsageException("could not execute",
                new SQLException((String) null, "23000", 1452));

        assertThat(CategorisationWriteFailure.isSignalledRefusal(withNullMessage)).isFalse();
        assertThat(CategorisationWriteFailure.isConstraintViolation(withNullMessage)).isTrue();
    }

    @Test
    @DisplayName("A nulled cause chain does not break the classification")
    void nullCausesAreHandled() {
        Throwable bare = new InvalidDataAccessResourceUsageException("no cause");

        assertThat(CategorisationWriteFailure.isSignalledRefusal(bare)).isFalse();
        assertThat(CategorisationWriteFailure.isConstraintViolation(bare)).isFalse();
    }
}
