package com.campuscoin.recent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class RecentActivityWriteFailureTest {

    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    private static SQLException missingTransactionForeignKey() {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`recent_activity`, CONSTRAINT `fk_recent_txn` FOREIGN KEY "
                        + "(`transaction_id`) REFERENCES `transactions` (`id`) ON DELETE CASCADE)",
                "23000", 1452);
    }

    private static SQLException duplicateRecentRow() {
        return new SQLException(
                "Duplicate entry '2-31-VIEWED' for key 'recent_activity.uk_recent'", "23000", 1062);
    }

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("Invalid recent-activity action"));

        assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(RecentActivityWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("The refusal is recognised whichever of the procedure's three SIGNALs fired")
    void everySignalledRefusalIsRecognised() {

        for (String message : new String[]{
                "Invalid recent-activity action",
                "Transaction does not exist",
                "BR-02: cannot record activity for a transaction owned by another student"}) {

            Throwable failure = new InvalidDataAccessResourceUsageException(
                    "could not execute statement", signalledRefusal(message));

            assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure))
                    .as("message=%s", message)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("The refusal is recognised when its message is not the procedure's current text")
    void rewordingDoesNotBreakTheClassification() {
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("some future wording the SQL was never asked about"));

        assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("The SQLSTATE is found even when the driver's exception is nested deeper")
    void nestingDoesNotHideTheSqlState() {
        SQLException inner = signalledRefusal("BR-02: cannot record activity for a transaction "
                + "owned by another student");
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("wrapped once", inner));

        assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("A missing transaction foreign key is recognised as a constraint violation")
    void missingTransactionIsARecognisedConstraint() {
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                missingTransactionForeignKey());

        assertThat(RecentActivityWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    @Test
    @DisplayName("A duplicate row on uk_recent is a recognised constraint violation")
    void duplicateRecentRowIsARecognisedConstraint() {

        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                duplicateRecentRow());

        assertThat(RecentActivityWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    @Test
    @DisplayName("The transaction foreign key is found even when it is not the outer exception's message")
    void foreignKeyIsFoundByConstraintName() {
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("nested", missingTransactionForeignKey()));

        assertThat(RecentActivityWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("Another table's constraint name is not mistaken for this module's foreign key")
    void unrelatedConstraintNameIsNotMatched() {
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new SQLException("Duplicate entry '2-TIP-12-0' for key "
                        + "'bookmarks.uk_bookmark_dedupe'", "23000", 1062));

        assertThat(RecentActivityWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    @Test
    @DisplayName("The two recognisers are distinguishable, so the order in the service decides")
    void theRecognisersAreDistinguishable() {
        Throwable signalled = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("Transaction does not exist"));
        Throwable constraint = new DataIntegrityViolationException("could not execute statement",
                missingTransactionForeignKey());

        assertThat(RecentActivityWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(RecentActivityWriteFailure.isConstraintViolation(signalled)).isFalse();
        assertThat(RecentActivityWriteFailure.isSignalledRefusal(constraint)).isFalse();
        assertThat(RecentActivityWriteFailure.isConstraintViolation(constraint)).isTrue();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreLeftAlone() {
        Throwable connectionLost = new org.springframework.dao.DataAccessResourceFailureException(
                "Communications link failure",
                new SQLException("Communications link failure", "08S01", 0));
        Throwable deadlock = new org.springframework.dao.CannotAcquireLockException(
                "Deadlock found when trying to get lock",
                new SQLException("Deadlock found", "40001", 1213));
        Throwable plain = new IllegalStateException("no sql at all");

        for (Throwable failure : new Throwable[]{connectionLost, deadlock, plain}) {
            assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
            assertThat(RecentActivityWriteFailure.isConstraintViolation(failure))
                    .as("type=%s", failure.getClass().getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A log-shaped string mentioning the constraint name is not matched")
    void aMessageAboutTheConstraintIsNotAViolation() {

        Throwable proseAboutTheKey = new IllegalStateException(
                "the write was rejected by fk_recent_txn, see the docs");

        assertThat(RecentActivityWriteFailure.isConstraintViolation(proseAboutTheKey)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreHandled() {
        Throwable withNullMessage = new InvalidDataAccessResourceUsageException("could not execute",
                new SQLException((String) null, "23000", 1452));

        assertThat(RecentActivityWriteFailure.isSignalledRefusal(withNullMessage)).isFalse();
        assertThat(RecentActivityWriteFailure.isConstraintViolation(withNullMessage)).isTrue();
    }

    @Test
    @DisplayName("A nulled cause chain does not break the classification")
    void nullCausesAreHandled() {
        Throwable bare = new InvalidDataAccessResourceUsageException("no cause");

        assertThat(RecentActivityWriteFailure.isSignalledRefusal(bare)).isFalse();
        assertThat(RecentActivityWriteFailure.isConstraintViolation(bare)).isFalse();
    }
}
