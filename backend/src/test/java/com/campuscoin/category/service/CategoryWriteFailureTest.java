package com.campuscoin.category.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class CategoryWriteFailureTest {

    private static SQLException duplicateKey() {
        return new SQLException(
                "Duplicate entry '2-EXPENSE-Coffee' for key 'categories.uk_categories_scope_type_name'",
                "23000", 1062);
    }

    private static SQLException restrictingForeignKey() {
        return new SQLException(
                "Cannot delete or update a parent row: a foreign key constraint fails "
                        + "(`campuscoin`.`transactions`, CONSTRAINT `fk_txn_category` FOREIGN KEY "
                        + "(`category_id`) REFERENCES `categories` (`id`))",
                "23000", 1451);
    }

    private static SQLException signalledRefusal() {
        return new SQLException(
                "BR-07: this category has a budget; disable it instead of deleting it", "45000");
    }

    @Test
    @DisplayName("A duplicate key on the name constraint is recognised through Spring's wrapper")
    void duplicateKeyIsRecognised() {

        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                duplicateKey());

        assertThat(CategoryWriteFailure.isUniqueNameViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("Another integrity violation is not reported as a name clash")
    void otherIntegrityViolationsAreNotNameClashes() {

        assertThat(CategoryWriteFailure.isUniqueNameViolation(
                new DataIntegrityViolationException("fk", restrictingForeignKey()))).isFalse();

        assertThat(CategoryWriteFailure.isUniqueNameViolation(
                new DataIntegrityViolationException("check",
                        new SQLException("Check constraint 'ck_budget_limit' is violated.",
                                "HY000", 3819)))).isFalse();
    }

    @Test
    @DisplayName("The constraint is found even when the driver's exception is nested deeper")
    void nestingDepthDoesNotHideTheConstraint() {

        Throwable failure = new DataIntegrityViolationException("outer",
                new IllegalStateException("middle", duplicateKey()));

        assertThat(CategoryWriteFailure.isUniqueNameViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal());

        assertThat(CategoryWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(CategoryWriteFailure.isUniqueNameViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A duplicate key is not mistaken for a trigger refusal")
    void duplicateKeyIsNotASignalledRefusal() {
        Throwable failure = new DataIntegrityViolationException("dup", duplicateKey());

        assertThat(CategoryWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(CategoryWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("The three recognisers are distinguishable, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {
        Throwable unique = new DataIntegrityViolationException("dup", duplicateKey());
        Throwable signalled = new InvalidDataAccessResourceUsageException("sig", signalledRefusal());
        Throwable foreignKey =
                new DataIntegrityViolationException("fk", restrictingForeignKey());

        assertThat(CategoryWriteFailure.isUniqueNameViolation(unique)).isTrue();
        assertThat(CategoryWriteFailure.isSignalledRefusal(unique)).isFalse();

        assertThat(CategoryWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(CategoryWriteFailure.isUniqueNameViolation(signalled)).isFalse();

        assertThat(CategoryWriteFailure.isConstraintViolation(foreignKey)).isTrue();
        assertThat(CategoryWriteFailure.isUniqueNameViolation(foreignKey)).isFalse();
        assertThat(CategoryWriteFailure.isSignalledRefusal(foreignKey)).isFalse();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreNotClaimed() {

        Throwable failure = new IllegalStateException("connection reset");

        assertThat(CategoryWriteFailure.isUniqueNameViolation(failure)).isFalse();
        assertThat(CategoryWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(CategoryWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {

        Throwable failure = new DataIntegrityViolationException("no message",
                new SQLException((String) null, "23000"));

        assertThat(CategoryWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(CategoryWriteFailure.isUniqueNameViolation(failure)).isFalse();
        assertThat(CategoryWriteFailure.isSignalledRefusal(failure)).isFalse();
    }
}
