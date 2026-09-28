package com.campuscoin.budget.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class BudgetWriteFailureTest {

    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    private static SQLException checkViolation(String constraint) {
        return new SQLException("Check constraint '" + constraint + "' is violated.", "HY000", 3819);
    }

    private static SQLException duplicateLimit() {
        return new SQLException(
                "Duplicate entry '7-3-2026-09-01' for key 'budgets.uk_budget_user_cat_month'",
                "23000", 1062);
    }

    private static SQLException duplicateSomethingElse() {
        return new SQLException("Duplicate entry '7' for key 'budgets.PRIMARY'", "23000", 1062);
    }

    @Test
    @DisplayName("A trigger's SIGNAL is recognised even though Spring does not call it an integrity error")
    void signalledRefusalIsRecognised() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-07: category has been disabled"));

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(BudgetWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Each of the procedure's four rules is recognised through the same branch")
    void everySignalledRuleIsRecognised() {

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

        Throwable failure = new InvalidDataAccessResourceUsageException("outer",
                new IllegalStateException("middle",
                        signalledRefusal("BR-02: category belongs to another student")));

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("Each CHECK on budgets is a constraint violation, not a trigger refusal")
    void everyCheckConstraintIsRecognisedAsSuch() {

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

        Throwable failure = new IllegalStateException("boom", duplicateLimit());

        assertThat(BudgetWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("BR-11: the unique key's name is recognised however the driver spells it")
    void theDuplicateLimitKeyIsRecognised() {

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

        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(duplicateSomethingElse())).isFalse();

        assertThat(BudgetWriteFailure.isConstraintViolation(duplicateSomethingElse())).isTrue();
    }

    @Test
    @DisplayName("A trigger refusal is not mistaken for the duplicate limit")
    void aRefusalIsNotADuplicate() {

        Throwable refusal = new InvalidDataAccessResourceUsageException("statement",
                signalledRefusal("BR-11: a budget may only be set on an expense category"));

        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(refusal)).isFalse();
        assertThat(BudgetWriteFailure.isSignalledRefusal(refusal)).isTrue();
    }

    private static SQLException duplicateLimitInUpperCase() {
        return new SQLException("Duplicate entry 'x' for key 'BUDGETS.UK_BUDGET_USER_CAT_MONTH'",
                "23000", 1062);
    }

    @Test
    @DisplayName("The two recognisers do not overlap, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {

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

        Throwable failure = new IllegalStateException("connection reset");

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(BudgetWriteFailure.isConstraintViolation(failure)).isFalse();
        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {

        Throwable failure = new InvalidDataAccessResourceUsageException("no message",
                new SQLException((String) null, "45000"));

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(BudgetWriteFailure.isConstraintViolation(failure)).isFalse();
        assertThat(BudgetWriteFailure.mentionsDuplicateLimit(failure)).isFalse();
    }

    @Test
    @DisplayName("A cause chain that loops back on itself terminates")
    void aSelfReferencingChainTerminates() {

        SQLException driver = new SQLException("loop", "45000", 1644);
        Throwable failure = new InvalidDataAccessResourceUsageException("outer", driver);

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();

        assertThat(BudgetWriteFailure.isSignalledRefusal(failure)).isTrue();
    }
}
