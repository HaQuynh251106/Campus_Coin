package com.campuscoin.recurring.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class RecurringRuleWriteFailureTest {

    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    private static SQLException checkViolation(String constraint) {
        return new SQLException("Check constraint '" + constraint + "' is violated.", "HY000", 3819);
    }

    private static SQLException duplicateKey() {
        return new SQLException("Duplicate entry '7' for key 'recurring_rules.PRIMARY'", "23000", 1062);
    }

    private static SQLException restrictingForeignKey() {
        return new SQLException(
                "Cannot delete or update a parent row: a foreign key constraint fails "
                        + "(`campuscoin`.`recurring_rules`, CONSTRAINT `fk_recurring_category` "
                        + "FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`))",
                "23000", 1451);
    }

    @Test
    @DisplayName("A trigger's SIGNAL is recognised even though Spring does not call it an integrity error")
    void signalledRefusalIsRecognised() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-07: category has been disabled"));

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(RecurringRuleWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Each of the procedure's four rules is recognised through the same branch")
    void everySignalledRuleIsRecognised() {

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

        Throwable failure = new InvalidDataAccessResourceUsageException("outer",
                new IllegalStateException("middle",
                        signalledRefusal("BR-02: category belongs to another student")));

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("Each CHECK on recurring_rules is a constraint violation, not a trigger refusal")
    void everyCheckConstraintIsRecognisedAsSuch() {

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

        Throwable failure = new IllegalStateException("boom", duplicateKey());

        assertThat(RecurringRuleWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("The two recognisers do not overlap, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {

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

        Throwable failure = new IllegalStateException("connection reset");

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(RecurringRuleWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {

        Throwable failure = new InvalidDataAccessResourceUsageException("no message",
                new SQLException((String) null, "45000"));

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(RecurringRuleWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A cause chain that loops back on itself terminates")
    void aSelfReferencingChainTerminates() {

        SQLException driver = new SQLException("loop", "45000", 1644);
        Throwable failure = new InvalidDataAccessResourceUsageException("outer", driver);

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("The retired-category helper is a convenience, not the branch the API depends on")
    void retiredCategoryHelperIsAdvisoryOnly() {

        Throwable retired = new InvalidDataAccessResourceUsageException("sig",
                signalledRefusal("BR-07: category has been disabled"));
        Throwable unrelated = new InvalidDataAccessResourceUsageException("sig",
                signalledRefusal("BR-02: category belongs to another student"));

        assertThat(RecurringRuleWriteFailure.mentionsRetiredCategory(retired)).isTrue();
        assertThat(RecurringRuleWriteFailure.mentionsRetiredCategory(unrelated)).isFalse();

        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(retired)).isTrue();
        assertThat(RecurringRuleWriteFailure.isSignalledRefusal(unrelated)).isTrue();

        assertThat(RecurringRuleWriteFailure.mentionsRetiredCategory(
                new IllegalStateException("outer", new SQLException((String) null, "45000"))))
                .isFalse();
    }
}
