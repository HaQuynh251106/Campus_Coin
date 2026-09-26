package com.campuscoin.categorisation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification for the two UC-08 statements, tested directly.
 *
 * <p>A plain unit test, deliberately: both recognisers take a {@code Throwable} and answer a boolean, so
 * nothing about them needs a database and no assertion here is weakened by the absence of one. What
 * matters is the shape of the exceptions, and that is reproduced exactly as MySQL and Spring produce it.
 *
 * <p><b>Why the classification has to exist at all.</b> The update on {@code transactions} runs through
 * {@code trg_transactions_before_update}, which calls {@code sp_validate_transaction} and can refuse
 * with {@code SIGNAL SQLSTATE '45000'} for BR-13 or BR-08. Neither is reachable through this API by
 * design - the category id is never taken from the client, and BR-08 is about a date module 4 already
 * validated - so what the classifier is for is recognising the state "the database refused this write"
 * and handing it the single answer {@code CategorisationService} gives, which is the record's own
 * {@code 404}. The tests below pin that, and pin the two things that must <em>not</em> be swept in
 * with it: a connection failure and a deadlock, which mean "try again" rather than "your record is
 * gone".
 *
 * <p>The nesting matters as much as the SQLSTATE. Spring wraps the driver's exception, sometimes more
 * than once, and the SQLSTATE is only on the innermost one; a test that passed a bare
 * {@code SQLException} would prove nothing about how the exceptions actually arrive.
 */
class CategorisationWriteFailureTest {

    /** What {@code SIGNAL SQLSTATE '45000'} becomes: no error number, and not an integrity error. */
    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    /** A missing parent row as MySQL reports it: 1452, SQLSTATE 23000, constraint named. */
    private static SQLException missingCategoryForeignKey(String constraint) {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`transactions`, CONSTRAINT `" + constraint + "` FOREIGN KEY "
                        + "(`ai_suggested_category_id`) REFERENCES `categories` (`id`))",
                "23000", 1452);
    }

    // ------------------------------------------------------------------
    //  A refusal from the trigger's validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {
        // InvalidDataAccessResourceUsageException -> SQLException 45000, which is how MySQL's SIGNAL
        // arrives: Spring reports "resource usage" rather than an integrity error.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-13: suggested category belongs to another student"));

        assertThat(CategorisationWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(CategorisationWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Both rules the validation can raise are recognised the same way")
    void everyRefusalIsRecognised() {
        // BR-13 and BR-08. The classifier reads neither text - and cannot act on any difference between
        // them even if it did - so both must classify identically. That equivalence is the security
        // property: a refusal of either kind is answered as the record's own "not found", so a caller
        // cannot use the difference to learn which of their own records exist.
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
        // The validation's prose is not a contract: it can be reworded without the API's behaviour
        // changing, so nothing may depend on the words.
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

    // ------------------------------------------------------------------
    //  Integrity violations
    // ------------------------------------------------------------------

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
        // fk_txn_ai_category and ck_txn_ai_conf on the record's update, fk_rule_category on the rule's
        // upsert. Telling them apart would change nothing - the answer is one message about the
        // suggestion - while adding a way to be wrong, which is why the classifier reads no name.
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
        // The persistence layer's choice of wrapper is not a contract either. Deciding by the standard
        // SQLSTATE is what makes the recognition survive a different exception type from the same
        // driver.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                new RuntimeException("nested", missingCategoryForeignKey("fk_rule_category")));

        assertThat(CategorisationWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  What must not be mislabelled
    // ------------------------------------------------------------------

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
        // A lost connection and a deadlock are the database being unavailable, not the database
        // refusing. Sweeping either in would turn "try again" into "your record is gone" - and for a
        // deadlock, which is exactly what a retry fixes, that would be the worst possible answer.
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
        // The name appearing in prose is not the database raising anything. A classifier keyed on the
        // substring would answer "refused" for a plain application error whose text happened to
        // mention the key - so the shape of the exception is the deciding factor, not the words.
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
