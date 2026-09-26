package com.campuscoin.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification for {@code sp_flag_transaction}, tested directly.
 *
 * <p>A plain unit test, deliberately: both recognisers take a {@code Throwable} and answer a boolean,
 * so nothing about them needs a database and no assertion here is weakened by the absence of one. What
 * matters is the shape of the exceptions, and that is reproduced exactly as MySQL and Spring produce
 * it.
 *
 * <p><b>Why the classification has to exist at all, given this API never produces the invalid type.</b>
 * The procedure raises {@code 45000} for three reasons - an invalid flag type, a missing transaction,
 * and another student's transaction. The first cannot be reached through this API, because the flag
 * type is a parsed Java enum the detector produced; the other two must be answered identically, since
 * separating them would let a caller learn which transaction identifiers exist. So the classifier's job
 * is not to tell three things apart but to recognise one state - "the database refused this write" -
 * and hand it the single answer {@code AnomalyService} gives. The tests below pin that, and pin the
 * two things that must <em>not</em> be swept in with it.
 *
 * <p>The nesting matters as much as the SQLSTATE. Spring wraps the driver's exception, sometimes more
 * than once, and the SQLSTATE is only on the innermost one; a test that passed a bare
 * {@code SQLException} would prove nothing about how the exceptions actually arrive.
 */
class AnomalyWriteFailureTest {

    /** What {@code SIGNAL SQLSTATE '45000'} becomes: no error number, and not an integrity error. */
    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    /** A missing parent row as MySQL reports it: 1452, SQLSTATE 23000, constraint named. */
    private static SQLException missingUserForeignKey() {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`transactions`, CONSTRAINT `fk_txn_user` FOREIGN KEY "
                        + "(`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE)",
                "23000", 1452);
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
                signalledRefusal("Invalid anomaly flag type"));

        assertThat(AnomalyWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(AnomalyWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Every refusal the procedure can raise is recognised the same way")
    void everyRefusalIsRecognised() {
        // The three texts the procedure raises. The classifier reads none of them - and cannot act on
        // any difference between them even if it did - so all three must classify identically. That
        // equivalence is the security property: "not yours" and "not there" are one answer.
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
        // The procedure's prose is not a contract: it can be reworded without the API's behaviour
        // changing, so nothing may depend on the words.
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

    // ------------------------------------------------------------------
    //  Integrity violations
    // ------------------------------------------------------------------

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
        // The persistence layer's choice of wrapper is not a contract either. Deciding by the standard
        // SQLSTATE is what makes the recognition survive a different exception type from the same
        // driver.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("nested", missingUserForeignKey()));

        assertThat(AnomalyWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("Another constraint is swept in, and that is deliberate")
    void anotherConstraintIsSweptInDeliberately() {
        // Stated as a test because it is a decision rather than an oversight. There is one answer to
        // give - "the record could not be marked" - so telling which constraint fired would change
        // nothing but would add a way to be wrong. Contrast `BookmarkWriteFailure`, which matches
        // names because there the name does change the answer.
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                new SQLException("Duplicate entry '2-31' for key 'recent_activity.uk_recent'",
                        "23000", 1062));

        assertThat(AnomalyWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(AnomalyWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    // ------------------------------------------------------------------
    //  What must not be mislabelled
    // ------------------------------------------------------------------

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
        // A lost connection and a deadlock are the database being unavailable, not the database
        // refusing. Sweeping either in would turn "try again" into "your record is gone".
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
        // The name appearing in prose is not the database raising anything. A classifier keyed on the
        // substring would answer "refused" for a plain application error whose text happened to
        // mention the key - so the shape of the exception is the deciding factor, not the words.
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
