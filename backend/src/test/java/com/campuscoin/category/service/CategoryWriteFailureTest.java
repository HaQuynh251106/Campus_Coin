package com.campuscoin.category.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification, tested directly.
 *
 * <p>A plain unit test, deliberately: the three recognisers take a {@code Throwable} and answer a
 * boolean, so nothing about them needs a database and no assertion here is weakened by the absence
 * of one. What matters is the shape of the exceptions, and that is reproduced exactly as MySQL and
 * Spring produce it.
 *
 * <p>The unique-key branch is the reason this class exists. Through the API it is nearly
 * unreachable: the service checks the name before inserting, so the constraint fires only if a
 * competing request commits in the gap. The concurrency test in {@code CategoryApiIT} shows the
 * right end state, but it cannot show that <em>this</em> branch ran - the pre-check usually wins.
 * Verified here instead, so the branch is not left to chance.
 *
 * <p>The nesting matters as much as the codes. Spring wraps the driver's exception, sometimes twice,
 * and the SQLSTATE is only on the innermost one. A test that passed a bare {@code SQLException}
 * would prove nothing about how the exceptions actually arrive.
 */
class CategoryWriteFailureTest {

    /** A duplicate key as MySQL reports it: 1062, SQLSTATE 23000, constraint named in the text. */
    private static SQLException duplicateKey() {
        return new SQLException(
                "Duplicate entry '2-EXPENSE-Coffee' for key 'categories.uk_categories_scope_type_name'",
                "23000", 1062);
    }

    /** A restricting foreign key as MySQL reports it: 1451, SQLSTATE 23000, constraint named. */
    private static SQLException restrictingForeignKey() {
        return new SQLException(
                "Cannot delete or update a parent row: a foreign key constraint fails "
                        + "(`campuscoin`.`transactions`, CONSTRAINT `fk_txn_category` FOREIGN KEY "
                        + "(`category_id`) REFERENCES `categories` (`id`))",
                "23000", 1451);
    }

    /** What {@code SIGNAL SQLSTATE '45000'} becomes: no error number, and not an integrity error. */
    private static SQLException signalledRefusal() {
        return new SQLException(
                "BR-07: this category has a budget; disable it instead of deleting it", "45000");
    }

    // ------------------------------------------------------------------
    //  The unique name constraint
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A duplicate key on the name constraint is recognised through Spring's wrapper")
    void duplicateKeyIsRecognised() {
        // DataIntegrityViolationException -> SQLException, which is how Hibernate/JPA hands it over.
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                duplicateKey());

        assertThat(CategoryWriteFailure.isUniqueNameViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("Another integrity violation is not reported as a name clash")
    void otherIntegrityViolationsAreNotNameClashes() {
        // Both are DataIntegrityViolationException and both carry SQLSTATE 23000, so a check that
        // stopped at either would call them name clashes. Telling a caller to choose a different
        // name when the name was never the problem is worse than saying nothing useful.
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
        // Hibernate sometimes wraps more than once; the cause chain is walked rather than a fixed
        // depth being assumed.
        Throwable failure = new DataIntegrityViolationException("outer",
                new IllegalStateException("middle", duplicateKey()));

        assertThat(CategoryWriteFailure.isUniqueNameViolation(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  Trigger refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {
        // This is the case that a type check alone would miss. Spring translates 45000 to
        // InvalidDataAccessResourceUsageException, not to DataIntegrityViolationException, so code
        // that caught the latter would let the refusal escape as a 500.
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

    // ------------------------------------------------------------------
    //  Ordering and the unrecognised case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The three recognisers are distinguishable, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {
        Throwable unique = new DataIntegrityViolationException("dup", duplicateKey());
        Throwable signalled = new InvalidDataAccessResourceUsageException("sig", signalledRefusal());
        Throwable foreignKey =
                new DataIntegrityViolationException("fk", restrictingForeignKey());

        // A duplicate key: only the unique-name rule.
        assertThat(CategoryWriteFailure.isUniqueNameViolation(unique)).isTrue();
        assertThat(CategoryWriteFailure.isSignalledRefusal(unique)).isFalse();

        // A SIGNAL: only the trigger rule. It also satisfies isConstraintViolation through its
        // SQLSTATE-free path being false, which is why the service checks the trigger first.
        assertThat(CategoryWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(CategoryWriteFailure.isUniqueNameViolation(signalled)).isFalse();

        // A restricting foreign key: the generic constraint case, and neither of the two above.
        assertThat(CategoryWriteFailure.isConstraintViolation(foreignKey)).isTrue();
        assertThat(CategoryWriteFailure.isUniqueNameViolation(foreignKey)).isFalse();
        assertThat(CategoryWriteFailure.isSignalledRefusal(foreignKey)).isFalse();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreNotClaimed() {
        // If every failure matched a rule, the service's final rethrow would be dead code and a
        // genuine fault would be reported as a business rule.
        Throwable failure = new IllegalStateException("connection reset");

        assertThat(CategoryWriteFailure.isUniqueNameViolation(failure)).isFalse();
        assertThat(CategoryWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(CategoryWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {
        // The driver does not always populate a message, and a contains() on null would throw
        // inside the translation - turning a refusal into a 500.
        Throwable failure = new DataIntegrityViolationException("no message",
                new SQLException((String) null, "23000"));

        assertThat(CategoryWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(CategoryWriteFailure.isUniqueNameViolation(failure)).isFalse();
        assertThat(CategoryWriteFailure.isSignalledRefusal(failure)).isFalse();
    }
}
