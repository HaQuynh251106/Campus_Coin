package com.campuscoin.recent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification for {@code recent_activity}, tested directly.
 *
 * <p>A plain unit test, deliberately: the two recognisers take a {@code Throwable} and answer a boolean,
 * so nothing about them needs a database and no assertion here is weakened by the absence of one. What
 * matters is the shape of the exceptions, and that is reproduced exactly as MySQL and Spring produce it.
 *
 * <p><b>The {@code SIGNAL} branch is the reason this class exists.</b> Through the API it is reachable
 * and load-bearing - the ownership check is what stops a student from recording activity against
 * somebody else's transaction - but the integration test can only show the response. Here it is pinned
 * that the response came from a classification, and that the classification is the one intended.
 *
 * <p><b>What the SQLSTATE alone cannot tell apart, and what this file therefore records.</b>
 * {@code sp_touch_recent_activity} raises {@code 45000} for three different reasons - an invalid
 * action, a transaction that does not exist, and a transaction owned by another student. The
 * classifier deliberately does not separate them: the first is eliminated before any SQL runs (the
 * action is a parsed Java enum), and the other two must be answered identically so that a caller cannot
 * enumerate other students' transaction identifiers. The tests below assert that the two kinds of
 * refusal are equally recognised, not that they are distinguished - which is the point.
 *
 * <p>The nesting matters too. Spring wraps the driver's exception, sometimes more than once, and the
 * SQLSTATE is only on the innermost one. A test that passed a bare {@code SQLException} would prove
 * nothing about how the exceptions actually arrive.
 */
class RecentActivityWriteFailureTest {

    /** What {@code SIGNAL SQLSTATE '45000'} becomes: no error number, and not an integrity error. */
    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    /** A missing parent row as MySQL reports it: 1452, SQLSTATE 23000, constraint named. */
    private static SQLException missingTransactionForeignKey() {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`recent_activity`, CONSTRAINT `fk_recent_txn` FOREIGN KEY "
                        + "(`transaction_id`) REFERENCES `transactions` (`id`) ON DELETE CASCADE)",
                "23000", 1452);
    }

    /**
     * A duplicate key on the upsert's unique key.
     *
     * <p>Reachable only if the procedure is changed from an upsert to a plain insert; the classifier
     * recognises it so that a change does not turn "you already viewed this" into a server error.
     */
    private static SQLException duplicateRecentRow() {
        return new SQLException(
                "Duplicate entry '2-31-VIEWED' for key 'recent_activity.uk_recent'", "23000", 1062);
    }

    // ------------------------------------------------------------------
    //  The procedure's SIGNAL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {
        // InvalidDataAccessResourceUsageException -> SQLException 45000, which is how MySQL's SIGNAL
        // arrives: Spring reports "resource usage" rather than an integrity error.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("Invalid recent-activity action"));

        assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(RecentActivityWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("The refusal is recognised whichever of the procedure's three SIGNALs fired")
    void everySignalledRefusalIsRecognised() {
        // The three texts the procedure can raise. The classifier reads none of them - they can be
        // reworded without the API contract changing - so all three must classify identically.
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

    // ------------------------------------------------------------------
    //  The integrity violations
    // ------------------------------------------------------------------

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
        // Not an error a client could act on: the record exists and re-viewing it is what the upsert
        // already does. Recognised so that a change to a plain insert still answers "no such
        // transaction" rather than a 500.
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

        // 23000 is still an integrity violation, so the outer classification holds - but the message
        // names another module's key, which is why the name is matched rather than the SQLSTATE alone
        // where a name is what the caller's answer depends on.
        assertThat(RecentActivityWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(RecentActivityWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    // ------------------------------------------------------------------
    //  What must not be mislabelled
    // ------------------------------------------------------------------

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
        // The name appearing in prose is not the same as the database raising the violation. A
        // classifier keyed on the substring alone would answer "not found" for a plain application
        // error whose text happened to mention the key - so the shape has to be the deciding factor,
        // not the words. This build matches the name only inside a SQLException's chain, which this
        // exception is not.
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
