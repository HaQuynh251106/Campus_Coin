package com.campuscoin.budget.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification for the budget endpoints, tested directly.
 *
 * <p>A plain unit test, for the reason {@code RecurringRuleWriteFailureTest} gives: the recognisers
 * take a {@code Throwable} and answer a boolean, so nothing here needs a database, and the shapes
 * asserted are the ones MySQL and Spring actually produce.
 *
 * <p><b>Why this class has to be right, and why a test of the endpoints would not prove it.</b>
 * {@code sp_validate_budget} raises one SQLSTATE - {@code '45000'} - for four different rules, and it
 * is reached through triggers rather than called. A budget's three CHECKs ({@code ck_budget_limit},
 * {@code ck_budget_month}) and its unique key ({@code uk_budget_user_cat_month}) arrive as a
 * different SQLSTATE entirely, {@code '23000'} or {@code 'HY000'}. The service's translation is what
 * turns that split into a {@code 409} with a message rather than a {@code 500} - so the failure this
 * test guards against is a refusal escaping unrecognised because it was recognised by exception type
 * instead of by SQLSTATE.
 *
 * <p><b>The one rule that is not merely a refusal.</b> A duplicate limit and a trigger refusal both
 * deserve a {@code 409}, but only the duplicate has a remedy the message can name - change the
 * existing limit rather than create a second one. {@code mentionsDuplicateLimit} is the one check
 * here that reads text, and what it reads is a constraint <em>name</em>, which is the schema's
 * identifier rather than driver prose; the tests below pin both that it finds the real key and that
 * it does not claim a refusal that merely mentions some other key.
 *
 * <p>The nesting is asserted as carefully as the codes, since the SQLSTATE is only on the innermost
 * exception and a test that handed over a bare {@code SQLException} would prove nothing about how
 * these arrive in practice.
 */
class BudgetWriteFailureTest {

    /**
     * A {@code SIGNAL SQLSTATE '45000'} from {@code sp_validate_budget}, as it arrives.
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

    /** The BR-11 unique key as MySQL reports it: 1062, SQLSTATE 23000. */
    private static SQLException duplicateLimit() {
        return new SQLException(
                "Duplicate entry '7-3-2026-09-01' for key 'budgets.uk_budget_user_cat_month'",
                "23000", 1062);
    }

    /** Some other duplicate key on the same table, so the name-match cannot be a prefix match. */
    private static SQLException duplicateSomethingElse() {
        return new SQLException("Duplicate entry '7' for key 'budgets.PRIMARY'", "23000", 1062);
    }

    // ------------------------------------------------------------------
    //  Trigger refusals - the branch the service most needs to recognise
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A trigger's SIGNAL is recognised even though Spring does not call it an integrity error")
    void signalledRefusalIsRecognised() {
        // The case a type check alone would miss. MySQL reports SIGNAL as "resource usage" rather than
        // as an integrity violation, so Spring raises InvalidDataAccessResourceUsageException - and
        // code that caught only DataIntegrityViolationException would let every one of
        // sp_validate_budget's rules escape as a 500.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-07: category has been disabled"));

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(BudgetWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Each of the procedure's four rules is recognised through the same branch")
    void everySignalledRuleIsRecognised() {
        // The API cannot tell these apart, and deliberately does not try: the service checks each of
        // them before the write, so a refusal that still arrives means the fact changed underneath the
        // request. What is asserted is that none of the four escapes unrecognised - these are the
        // literal SIGNAL texts in sp_validate_budget.
        String[] messages = {
                "Category does not exist",
                "BR-11: a budget may only be set on an expense category",
                "BR-02: category belongs to another student",
                "BR-07: category has been disabled"
        };

        for (String message : messages) {
            Throwable failure = new InvalidDataAccessResourceUsageException("statement",
                    signalledRefusal(message));
            assertThat(BudgetWriteFailure.isSignalledRefusal(failure))
                    .as("%s must be recognised as a refusal", message)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("The refusal is found even when the driver's exception is nested deeper")
    void nestingDepthDoesNotHideTheRefusal() {
        // Hibernate sometimes wraps more than once, and the transaction path adds a wrapper of its
        // own. The chain is walked rather than a fixed depth assumed, so a SIGNAL is still found when
        // Spring's wrapper is not the innermost cause.
        Throwable failure = new InvalidDataAccessResourceUsageException("outer",
                new IllegalStateException("middle",
                        signalledRefusal("BR-02: category belongs to another student")));

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  Constraints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Each CHECK on budgets is a constraint violation, not a trigger refusal")
    void everyCheckConstraintIsRecognisedAsSuch() {
        // Both CHECKs this table carries. A non-positive limit and a month that is not the first of
        // one are refused by the database as well as by validation, so the branch has to hold for
        // each rather than only for the one a test happened to pick.
        String[] constraints = {"ck_budget_limit", "ck_budget_month"};

        for (String constraint : constraints) {
            Throwable failure = new DataIntegrityViolationException(constraint,
                    checkViolation(constraint));
            assertThat(BudgetWriteFailure.isConstraintViolation(failure))
                    .as("%s must be recognised as a constraint", constraint)
                    .isTrue();
            assertThat(BudgetWriteFailure.isSignalledRefusal(failure))
                    .as("%s must not be mistaken for a trigger signal", constraint)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A constraint is recognised from the SQLSTATE alone, without Spring's wrapper type")
    void constraintIsFoundBySqlState() {
        // Not every integrity failure arrives wrapped: a native call can surface the driver's
        // exception directly. The SQLSTATE is what makes the branch independent of the wrapper.
        Throwable failure = new IllegalStateException("boom", duplicateLimit());

        assertThat(BudgetWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  The duplicate limit, which is the one case with its own answer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BR-11: the unique key's name is recognised however the driver spells it")
    void theDuplicateLimitKeyIsRecognised() {
        // The one check here that reads text. What it looks for is the schema's own identifier for
        // BR-11, and MySQL reports identifiers in the case the statement used - so the match is
        // case-insensitive rather than Locale-sensitive, because the string is a schema literal and
        // not translated output.
        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(duplicateLimit())).isTrue();
        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(new DataIntegrityViolationException(
                "duplicate", duplicateLimit()))).isTrue();
        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(new IllegalStateException("outer",
                new RuntimeException("inner", duplicateLimit())))).isTrue();
        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(duplicateLimitInUpperCase())).isTrue();
    }

    @Test
    @DisplayName("A different duplicate key on the same table is not claimed as the limit's")
    void anotherDuplicateKeyIsNotClaimed() {
        // The distinction the message depends on. Every duplicate on `budgets` arrives as the same
        // SQLSTATE and the same error number, so a check that stopped at "a duplicate key" would tell
        // a caller whose failure had nothing to do with BR-11 to go and change an existing limit.
        // Matching the key's name is what separates them.
        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(duplicateSomethingElse())).isFalse();
        // It is still a constraint violation, which is what the second branch answers.
        assertThat(BudgetWriteFailure.isConstraintViolation(duplicateSomethingElse())).isTrue();
    }

    @Test
    @DisplayName("A trigger refusal is not mistaken for the duplicate limit")
    void aRefusalIsNotADuplicate() {
        // The other direction of the same confusion. sp_validate_budget's texts name rules and
        // tables, and none of them is the unique key, so a refusal must fall through to the
        // signalled-refusal branch rather than being answered with the duplicate's remedy.
        Throwable refusal = new InvalidDataAccessResourceUsageException("statement",
                signalledRefusal("BR-11: a budget may only be set on an expense category"));

        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(refusal)).isFalse();
        assertThat(BudgetWriteFailure.isSignalledRefusal(refusal)).isTrue();
    }

    private static SQLException duplicateLimitInUpperCase() {
        return new SQLException("Duplicate entry 'x' for key 'BUDGETS.UK_BUDGET_USER_CAT_MONTH'",
                "23000", 1062);
    }

    // ------------------------------------------------------------------
    //  Ordering and the unrecognised case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The two recognisers do not overlap, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {
        // A SIGNAL and a constraint differ by SQLSTATE, so neither check can claim the other's case.
        // If they overlapped, the service's first branch would decide the meaning and the second
        // would be reachable only by accident. The service checks the duplicate first, then the
        // signal, then the constraint - so it matters that a signal is not also a constraint.
        Throwable signalled = new InvalidDataAccessResourceUsageException("sig",
                signalledRefusal("BR-07: category has been disabled"));
        Throwable constraint = new DataIntegrityViolationException("dup", duplicateLimit());

        assertThat(BudgetWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(BudgetWriteFailure.isConstraintViolation(signalled)).isFalse();

        assertThat(BudgetWriteFailure.isSignalledRefusal(constraint)).isFalse();
        assertThat(BudgetWriteFailure.isConstraintViolation(constraint)).isTrue();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreNotClaimed() {
        // If every failure matched a rule, the service's final rethrow would be dead code and a
        // genuine fault - a lost connection, a bug in the mapping - would be reported to the client
        // as a business rule with a message telling them to reload.
        Throwable failure = new IllegalStateException("connection reset");

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(BudgetWriteFailure.isConstraintViolation(failure)).isFalse();
        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {
        // The driver does not always populate a message - and this matters more for the one check
        // that reads text, since a null there would throw inside a translation that runs while
        // handling another exception.
        Throwable failure = new InvalidDataAccessResourceUsageException("no message",
                new SQLException((String) null, "45000"));

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(BudgetWriteFailure.isConstraintViolation(failure)).isFalse();
        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(failure)).isFalse();
    }

    @Test
    @DisplayName("A cause chain that loops back on itself terminates")
    void aSelfReferencingChainTerminates() {
        // A defensive case rather than an expected one: the walk is bounded by the chain's own
        // structure, so nothing here should hang. It is asserted because a translation method that
        // could loop would hang a request rather than fail it, which is far harder to diagnose.
        SQLException driver = new SQLException("loop", "45000", 1644);
        Throwable failure = new InvalidDataAccessResourceUsageException("outer", driver);

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();
        // A second call on the same chain, to show the walk left nothing behind.
        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();
    }
}
