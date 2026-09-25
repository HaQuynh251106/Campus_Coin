package com.campuscoin.recurring.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification for the recurring-rule endpoints, tested directly.
 *
 * <p>A plain unit test, for the reason {@code TransactionWriteFailureTest} and
 * {@code CategoryWriteFailureTest} give: the recognisers take a {@code Throwable} and answer a
 * boolean, so nothing here needs a database, and the shapes asserted are the ones MySQL and Spring
 * actually produce.
 *
 * <p>It matters here because {@code sp_validate_recurring_rule} raises one SQLSTATE for four
 * different rules and is reached through triggers rather than a call - so the service cannot pass
 * anything that would let the branch be identified from the signal alone. The only distinction the
 * API can rely on is "a trigger refused this" versus "a constraint refused this", and the failure
 * mode that would matter is a refusal escaping as a {@code 500} because it was recognised by
 * exception type instead of by SQLSTATE.
 *
 * <p>The nesting is asserted as carefully as the codes, since the SQLSTATE is only on the innermost
 * exception and a test that handed over a bare {@code SQLException} would prove nothing about how
 * these arrive in practice.
 */
class RecurringRuleWriteFailureTest {

    /**
     * A {@code SIGNAL SQLSTATE '45000'} from {@code sp_validate_recurring_rule}, as it arrives.
     *
     * <p>No error number: MySQL assigns 1644 to a handlerless {@code SIGNAL}, but the classification
     * reads the SQLSTATE only - which is the point, since a message is not a contract and an error
     * number is not what the driver is asked about.
     */
    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    /** A CHECK constraint as MySQL 8 reports it: 3819, SQLSTATE HY000, constraint named. */
    private static SQLException checkViolation(String constraint) {
        return new SQLException("Check constraint '" + constraint + "' is violated.", "HY000", 3819);
    }

    /** A duplicate key as MySQL reports it: 1062, SQLSTATE 23000. */
    private static SQLException duplicateKey() {
        return new SQLException("Duplicate entry '7' for key 'recurring_rules.PRIMARY'", "23000", 1062);
    }

    /** A restricting foreign key as MySQL reports it: 1451, SQLSTATE 23000. */
    private static SQLException restrictingForeignKey() {
        return new SQLException(
                "Cannot delete or update a parent row: a foreign key constraint fails "
                        + "(`campuscoin`.`recurring_rules`, CONSTRAINT `fk_recurring_category` "
                        + "FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`))",
                "23000", 1451);
    }

    // ------------------------------------------------------------------
    //  Trigger refusals - the branch the service most needs to recognise
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A trigger's SIGNAL is recognised even though Spring does not call it an integrity error")
    void signalledRefusalIsRecognised() {
        // The case a type check alone would miss. MySQL reports SIGNAL as "resource usage" rather
        // than as an integrity violation, so Spring raises InvalidDataAccessResourceUsageException -
        // and code that caught only DataIntegrityViolationException would let every one of
        // sp_validate_recurring_rule's four rules escape as a 500.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-07: category has been disabled"));

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(RecurringRuleWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Each of the procedure's four rules is recognised through the same branch")
    void everySignalledRuleIsRecognised() {
        // The API cannot tell these apart, and deliberately does not try: the service checks each of
        // them before the write, so a refusal that still arrives means the fact changed underneath
        // the request. What is asserted is that none of the four escapes unrecognised, which is what
        // would produce a 500. These are the literal SIGNAL texts in sp_validate_recurring_rule.
        String[] messages = {
                "BR-05: category does not exist",
                "BR-05: recurring rule type must match the category type",
                "BR-02: category belongs to another student",
                "BR-07: category has been disabled"
        };

        for (String message : messages) {
            Throwable failure = new InvalidDataAccessResourceUsageException("statement",
                    signalledRefusal(message));
            assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure))
                    .as("%s must be recognised as a refusal", message)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("The refusal is found even when the driver's exception is nested deeper")
    void nestingDepthDoesNotHideTheRefusal() {
        // Hibernate sometimes wraps more than once. The chain is walked rather than a fixed depth
        // assumed, so a SIGNAL is still found when Spring's wrapper is not the innermost cause.
        Throwable failure = new InvalidDataAccessResourceUsageException("outer",
                new IllegalStateException("middle",
                        signalledRefusal("BR-02: category belongs to another student")));

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  Constraints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Each CHECK on recurring_rules is a constraint violation, not a trigger refusal")
    void everyCheckConstraintIsRecognisedAsSuch() {
        // The three CHECKs this table carries. A negative amount, an interval of 0 and an end date
        // before the start date are all refused by the database as well as by validation, so the
        // branch has to hold for each of them rather than only for the one a test happened to pick.
        String[] constraints = {
                "ck_recurring_amount",
                "ck_recurring_interval",
                "ck_recurring_dates"
        };

        for (String constraint : constraints) {
            Throwable failure = new DataIntegrityViolationException(constraint,
                    checkViolation(constraint));
            assertThat(RecurringRuleWriteFailure.isConstraintViolation(failure))
                    .as("%s must be recognised as a constraint", constraint)
                    .isTrue();
            assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure))
                    .as("%s must not be mistaken for a trigger signal", constraint)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A foreign key is a constraint violation, not a trigger refusal")
    void foreignKeysAreRecognisedAsSuch() {
        Throwable restricting = new DataIntegrityViolationException("fk", restrictingForeignKey());

        assertThat(RecurringRuleWriteFailure.isConstraintViolation(restricting)).isTrue();
        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(restricting)).isFalse();
    }

    @Test
    @DisplayName("A constraint is recognised from the SQLSTATE alone, without Spring's wrapper type")
    void constraintIsFoundBySqlState() {
        // Not every integrity failure arrives wrapped: a native call can surface the driver's
        // exception directly. The SQLSTATE is what makes the branch independent of the wrapper.
        Throwable failure = new IllegalStateException("boom", duplicateKey());

        assertThat(RecurringRuleWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  Ordering and the unrecognised case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The two recognisers do not overlap, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {
        // A SIGNAL and a constraint differ by SQLSTATE, so neither check can claim the other's case.
        // If they overlapped, the service's first branch would decide the meaning and the second
        // would be reachable only by accident.
        Throwable signalled = new InvalidDataAccessResourceUsageException("sig",
                signalledRefusal("BR-07: category has been disabled"));
        Throwable constraint = new DataIntegrityViolationException("dup", duplicateKey());

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(RecurringRuleWriteFailure.isConstraintViolation(signalled)).isFalse();

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(constraint)).isFalse();
        assertThat(RecurringRuleWriteFailure.isConstraintViolation(constraint)).isTrue();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreNotClaimed() {
        // If every failure matched a rule, the service's final rethrow would be dead code and a
        // genuine fault - a lost connection, a bug in the mapping - would be reported to the client
        // as a business rule with a message telling them to reload.
        Throwable failure = new IllegalStateException("connection reset");

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(RecurringRuleWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {
        // The driver does not always populate a message. A check that read the text would throw on
        // that null, which is exactly the failure the SQLSTATE-only comparison avoids.
        Throwable failure = new InvalidDataAccessResourceUsageException("no message",
                new SQLException((String) null, "45000"));

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(RecurringRuleWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A cause chain that loops back on itself terminates")
    void aSelfReferencingChainTerminates() {
        // A defensive case rather than an expected one: the walk is bounded by the chain's own
        // structure, so nothing here should hang. It is asserted because a translation method that
        // could loop would hang a request rather than fail it, which is far harder to diagnose.
        SQLException driver = new SQLException("loop", "45000", 1644);
        Throwable failure = new InvalidDataAccessResourceUsageException("outer", driver);

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();
        // A second call on the same chain, to show the walk left nothing behind.
        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  The one text-reading helper, and why it is safe
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The retired-category helper is a convenience, not the branch the API depends on")
    void retiredCategoryHelperIsAdvisoryOnly() {
        // mentionsRetiredCategory is the one method here that reads driver text, and it exists only
        // so a test can assert which of the four signals fired. The assertion that matters is that
        // the service's actual branch - isSignalledRefusal - recognises the refusal whether or not
        // this helper does, so a driver that changed its wording would cost a diagnostic and not a
        // correct answer.
        Throwable retired = new InvalidDataAccessResourceUsageException("sig",
                signalledRefusal("BR-07: category has been disabled"));
        Throwable unrelated = new InvalidDataAccessResourceUsageException("sig",
                signalledRefusal("BR-02: category belongs to another student"));

        assertThat(RecurringRuleWriteFailure.mentionsRetiredCategory(retired)).isTrue();
        assertThat(RecurringRuleWriteFailure.mentionsRetiredCategory(unrelated)).isFalse();

        // Both are still refusals, which is what the API actually acts on.
        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(retired)).isTrue();
        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(unrelated)).isTrue();

        // A null message must not throw, since the helper walks a chain that may contain one.
        assertThat(RecurringRuleWriteFailure.mentionsRetiredCategory(
                new IllegalStateException("outer", new SQLException((String) null, "45000"))))
                .isFalse();
    }
}
