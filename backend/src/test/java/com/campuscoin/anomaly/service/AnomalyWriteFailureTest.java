package com.campuscoin.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class AnomalyWriteFailureTest {

    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    private static SQLException missingUserForeignKey() {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`transactions`, CONSTRAINT `fk_txn_user` FOREIGN KEY "
                        + "(`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE)",
                "23000", 1452);
    }

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("Invalid anomaly flag type"));

        assertThat(AnomalyWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(AnomalyWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Every refusal the procedure can raise is recognised the same way")
    void everyRefusalIsRecognised() {

        for (String message : new String[]{
                "Invalid anomaly flag type",
                "Transaction does not exist",
                "BR-02: cannot flag a transaction owned by another student"}) {

            Throwable failure = new InvalidDataAccessResourceUsageException(
                    "could not execute statement", signalledRefusal(message));

            assertThat(AnomalyWriteFailure.isSignalledRefusal(failure))
                    .as("message=%s", message)
                    .isTrue();
            assertThat(AnomalyWriteFailure.isConstraintViolation(failure))
                    .as("message=%s", message)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("The refusal is recognised when its message is not the procedure's current text")
    void rewordingDoesNotBreakTheClassification() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("some future wording the SQL was never asked about"));

        assertThat(AnomalyWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("The SQLSTATE is found even when the driver's exception is nested deeper")
    void nestingDoesNotHideTheSqlState() {
        SQLException inner = signalledRefusal(
                "BR-02: cannot flag a transaction owned by another student");
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("wrapped once", inner));

        assertThat(AnomalyWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("A foreign key violation is recognised as a constraint violation")
    void foreignKeyViolationIsARecognisedConstraint() {
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                missingUserForeignKey());

        assertThat(AnomalyWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(AnomalyWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    @Test
    @DisplayName("The SQLSTATE is enough on its own, whichever SQLException carries it")
    void theSqlStateIsEnoughWithoutTheType() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("nested", missingUserForeignKey()));

        assertThat(AnomalyWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("Another constraint is swept in, and that is deliberate")
    void anotherConstraintIsSweptInDeliberately() {

        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                new SQLException("Duplicate entry '2-31' for key 'recent_activity.uk_recent'",
                        "23000", 1062));

        assertThat(AnomalyWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(AnomalyWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    @Test
    @DisplayName("The two recognisers are distinguishable, so the order at the call site decides")
    void theRecognisersAreDistinguishable() {
        Throwable signalled = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("Transaction does not exist"));
        Throwable constraint = new DataIntegrityViolationException("could not execute statement",
                missingUserForeignKey());

        assertThat(AnomalyWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(AnomalyWriteFailure.isConstraintViolation(signalled)).isFalse();
        assertThat(AnomalyWriteFailure.isSignalledRefusal(constraint)).isFalse();
        assertThat(AnomalyWriteFailure.isConstraintViolation(constraint)).isTrue();
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
            assertThat(AnomalyWriteFailure.isSignalledRefusal(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
            assertThat(AnomalyWriteFailure.isConstraintViolation(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A log-shaped string mentioning a constraint name is not a violation")
    void aMessageAboutTheConstraintIsNotAViolation() {

        Throwable proseAboutTheKey = new IllegalStateException(
                "the write was rejected by fk_txn_user, see the docs");

        assertThat(AnomalyWriteFailure.isConstraintViolation(proseAboutTheKey)).isFalse();
        assertThat(AnomalyWriteFailure.isSignalledRefusal(proseAboutTheKey)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreHandled() {
        Throwable withNullMessage = new InvalidDataAccessResourceUsageException("could not execute",
                new SQLException((String) null, "23000", 1452));

        assertThat(AnomalyWriteFailure.isSignalledRefusal(withNullMessage)).isFalse();
        assertThat(AnomalyWriteFailure.isConstraintViolation(withNullMessage)).isTrue();
    }

    @Test
    @DisplayName("A nulled cause chain does not break the classification")
    void nullCausesAreHandled() {
        Throwable bare = new InvalidDataAccessResourceUsageException("no cause");

        assertThat(AnomalyWriteFailure.isSignalledRefusal(bare)).isFalse();
        assertThat(AnomalyWriteFailure.isConstraintViolation(bare)).isFalse();
    }
}
