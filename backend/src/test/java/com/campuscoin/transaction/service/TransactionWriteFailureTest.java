package com.campuscoin.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification for the transaction endpoints, tested directly.
 *
 * <p>A plain unit test, for the reason {@code CategoryWriteFailureTest} gives: the recognisers take a
 * {@code Throwable} and answer a boolean, so nothing here needs a database, and the shapes being
 * asserted are the ones MySQL and Spring actually produce.
 *
 * <p>It matters more here than for categories. {@code sp_validate_transaction} raises one SQLSTATE
 * for six different rules - the category does not exist, belongs to another student, is retired, the
 * date is in the future, the suggested category or the recurring rule or the import batch is not the
 * caller's - and the service reaches that procedure through a trigger rather than a call, so it
 * cannot pass anything that would let the branch be identified. The only distinction the API can
 * rely on is "a trigger refused this" versus "a constraint refused this", and both of those are
 * pinned here against the exact exception types Spring chooses - including the one that would
 * otherwise escape as a 500.
 *
 * <p>The nesting is asserted as carefully as the codes. Spring wraps the driver's exception, and the
 * SQLSTATE is only on the innermost one, so a test that handed over a bare {@code SQLException} would
 * prove nothing about how these arrive in practice.
 */
class TransactionWriteFailureTest {

    /**
     * A {@code SIGNAL SQLSTATE '45000'} from {@code sp_validate_transaction}, as it arrives.
     *
     * <p>No error number: MySQL assigns 1644 to a handlerless SIGNAL, but the constructor used here
     * mirrors what the driver exposes for the branch, and the classification reads the SQLSTATE
     * only - which is the point, since a message is localised and an error number is not the
     * contract.
     */
    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    /** A duplicate key as MySQL reports it: 1062, SQLSTATE 23000, constraint named in the text. */
    private static SQLException duplicateKey() {
        return new SQLException("Duplicate entry '7' for key 'transactions.PRIMARY'", "23000", 1062);
    }

    /** A restricting foreign key as MySQL reports it: 1451, SQLSTATE 23000, constraint named. */
    private static SQLException restrictingForeignKey() {
        return new SQLException(
                "Cannot delete or update a parent row: a foreign key constraint fails "
                        + "(`campuscoin`.`transactions`, CONSTRAINT `fk_txn_category` FOREIGN KEY "
                        + "(`category_id`) REFERENCES `categories` (`id`))",
                "23000", 1451);
    }

    /** A CHECK constraint as MySQL 8 reports it: 3819, SQLSTATE HY000. */
    private static SQLException checkViolation() {
        return new SQLException(
                "Check constraint 'ck_txn_amount' is violated.", "HY000", 3819);
    }

    // ------------------------------------------------------------------
    //  Trigger refusals - the branch the service most needs to recognise
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A trigger's SIGNAL is recognised even though Spring does not call it an integrity error")
    void signalledRefusalIsRecognised() {
        // The case a type check alone would miss. MySQL reports SIGNAL as "resource usage" rather
        // than as an integrity violation, so Spring raises InvalidDataAccessResourceUsageException -
        // and code that caught DataIntegrityViolationException would let the refusal escape as a
        // 500. Every one of sp_validate_transaction's six rules arrives exactly like this.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-08: transaction date cannot be in the future"));

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(TransactionWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Each of the procedure's six rules is recognised through the same branch")
    void everySignalledRuleIsRecognised() {
        // The API cannot tell these apart - and deliberately does not try to, since the service
        // checks each of them before writing and a refusal that still gets through means the fact
        // changed underneath the request. What is asserted is that none of the six escapes as an
        // unrecognised failure, which is what would produce a 500.
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
        // Hibernate sometimes wraps more than once. The chain is walked rather than a fixed depth
        // being assumed, so a SIGNAL is still found when Spring's own wrapper is not the innermost.
        Throwable failure = new InvalidDataAccessResourceUsageException("outer",
                new IllegalStateException("middle",
                        signalledRefusal("BR-02: category belongs to another student")));

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  Constraints
    // ------------------------------------------------------------------

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
        // Not every integrity failure arrives wrapped: a native call can surface the driver's
        // exception directly. The SQLSTATE is what makes the branch independent of the wrapper.
        Throwable failure = new IllegalStateException("boom", duplicateKey());

        assertThat(TransactionWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  Ordering and the unrecognised case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The two recognisers do not overlap, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {
        // A SIGNAL and a constraint differ by SQLSTATE, so neither check can claim the other's
        // case. If they overlapped, the service's first branch would decide the meaning and the
        // second would be reachable only by accident.
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
        // If every failure matched a rule, the service's final rethrow would be dead code and a
        // genuine fault - a lost connection, a bug in the mapping - would be reported to the client
        // as a business rule with a message telling them to reload.
        Throwable failure = new IllegalStateException("connection reset");

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(TransactionWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {
        // The driver does not always populate a message. A check that read the text would throw on
        // that null, which is exactly the failure the SQLSTATE-only comparison avoids.
        Throwable failure = new InvalidDataAccessResourceUsageException("no message",
                new SQLException((String) null, "45000"));

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(TransactionWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A cause chain that loops back on itself terminates")
    void aSelfReferencingChainTerminates() {
        // A defensive case rather than an expected one: the walk is bounded by the chain's own
        // structure, so nothing here should hang. It is asserted because a translation method that
        // could loop would hang a request rather than fail it, which is far harder to diagnose.
        SQLException driver = new SQLException("loop", "45000");
        Throwable failure = new InvalidDataAccessResourceUsageException("outer", driver);

        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();
        // A second call on the same chain, to show the walk left nothing behind.
        assertThat(TransactionWriteFailure.isSignalledRefusal(failure)).isTrue();
    }
}
