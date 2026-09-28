package com.campuscoin.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class TransactionWriteFailureTest {

    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    private static SQLException duplicateKey() {
        return new SQLException("Duplicate entry '7' for key 'transactions.PRIMARY'", "23000", 1062);
    }

    private static SQLException restrictingForeignKey() {
        return new SQLException(
                "Cannot delete or update a parent row: a foreign key constraint fails "
                        + "(`campuscoin`.`transactions`, CONSTRAINT `fk_txn_category` FOREIGN KEY "
                        + "(`category_id`) REFERENCES `categories` (`id`))",
                "23000", 1451);
    }

    private static SQLException checkViolation() {
        return new SQLException(
                "Check constraint 'ck_txn_amount' is violated.", "HY000", 3819);
    }

    @Test
    @DisplayName("A trigger's SIGNAL is recognised even though Spring does not call it an integrity error")
    void signalledRefusalIsRecognised() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-08: transaction date cannot be in the future"));

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(TransactionWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Each of the procedure's six rules is recognised through the same branch")
    void everySignalledRuleIsRecognised() {

        String[] messages = {
                "BR-05: category does not exist",
                "BR-02: category belongs to another student",
                "BR-07: category has been disabled",
                "BR-08: transaction date cannot be in the future",
                "BR-13: suggested category belongs to another student",
                "BR-02: recurring rule belongs to another student"
        };

        for (String message : messages) {
            Throwable failure = new InvalidDataAccessResourceUsageException("statement",
                    signalledRefusal(message));
            assertThat(TransactionWriteFailure.isSignalledRefusal(failure))
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

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("A CHECK or a foreign key is a constraint violation, not a trigger refusal")
    void constraintsAreRecognisedAsSuch() {
        Throwable check = new DataIntegrityViolationException("check", checkViolation());
        Throwable foreignKey = new DataIntegrityViolationException("fk", restrictingForeignKey());

        assertThat(TransactionWriteFailure.isConstraintViolation(check)).isTrue();
        assertThat(TransactionWriteFailure.isSignalledRefusal(check)).isFalse();

        assertThat(TransactionWriteFailure.isConstraintViolation(foreignKey)).isTrue();
        assertThat(TransactionWriteFailure.isSignalledRefusal(foreignKey)).isFalse();
    }

    @Test
    @DisplayName("A constraint is recognised from the SQLSTATE alone, without Spring's wrapper type")
    void constraintIsFoundBySqlState() {

        Throwable failure = new IllegalStateException("boom", duplicateKey());

        assertThat(TransactionWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("The two recognisers do not overlap, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {

        Throwable signalled = new InvalidDataAccessResourceUsageException("sig",
                signalledRefusal("BR-07: category has been disabled"));
        Throwable constraint = new DataIntegrityViolationException("dup", duplicateKey());

        assertThat(TransactionWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(TransactionWriteFailure.isConstraintViolation(signalled)).isFalse();

        assertThat(TransactionWriteFailure.isSignalledRefusal(constraint)).isFalse();
        assertThat(TransactionWriteFailure.isConstraintViolation(constraint)).isTrue();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreNotClaimed() {

        Throwable failure = new IllegalStateException("connection reset");

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(TransactionWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {

        Throwable failure = new InvalidDataAccessResourceUsageException("no message",
                new SQLException((String) null, "45000"));

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(TransactionWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A cause chain that loops back on itself terminates")
    void aSelfReferencingChainTerminates() {

        SQLException driver = new SQLException("loop", "45000");
        Throwable failure = new InvalidDataAccessResourceUsageException("outer", driver);

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();
    }
}
