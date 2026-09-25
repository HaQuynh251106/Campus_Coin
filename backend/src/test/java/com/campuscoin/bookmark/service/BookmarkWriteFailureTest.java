package com.campuscoin.bookmark.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification for {@code bookmarks}, tested directly.
 *
 * <p>A plain unit test, deliberately: the three recognisers take a {@code Throwable} and answer a
 * boolean, so nothing about them needs a database and no assertion here is weakened by the absence of
 * one. What matters is the shape of the exceptions, and that is reproduced exactly as MySQL and Spring
 * produce it.
 *
 * <p><b>The duplicate-key branch is the reason this class exists.</b> Through the API it is nearly
 * unreachable: the service checks for an existing bookmark before inserting, so
 * {@code uk_bookmark_dedupe} fires only if a competing request commits in the gap between the check and
 * the insert. A concurrency test can show the right end state but cannot show that <em>this</em> branch
 * ran. Verified here instead, so the branch is not left to chance.
 *
 * <p><b>The foreign-key branch matters as much as the duplicate one, and for the opposite reason.</b>
 * Both arrive as {@code DataIntegrityViolationException} with SQLSTATE 23000, and both mean something
 * completely different to the caller: "you already saved this" against "there is no such tip". A
 * classifier that stopped at the SQLSTATE would tell a student to look in a list that does not contain
 * the item - so the constraint's own name has to be present for the first, and absent for the second.
 *
 * <p>The nesting matters too. Spring wraps the driver's exception, sometimes twice, and the SQLSTATE is
 * only on the innermost one. A test that passed a bare {@code SQLException} would prove nothing about
 * how the exceptions actually arrive.
 */
class BookmarkWriteFailureTest {

    /** A duplicate key as MySQL reports it: 1062, SQLSTATE 23000, constraint named in the text. */
    private static SQLException duplicateKey() {
        return new SQLException(
                "Duplicate entry '2-TIP-12-0' for key 'bookmarks.uk_bookmark_dedupe'",
                "23000", 1062);
    }

    /** A missing parent row as MySQL reports it: 1452, SQLSTATE 23000, constraint named. */
    private static SQLException missingTipForeignKey() {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`bookmarks`, CONSTRAINT `fk_bookmark_tip` FOREIGN KEY "
                        + "(`tip_id`) REFERENCES `user_tips` (`id`) ON DELETE CASCADE)",
                "23000", 1452);
    }

    /** A CHECK violation: SQLSTATE 23000 in MySQL 8, but a different error number and no key name. */
    private static SQLException targetCheckViolation() {
        return new SQLException(
                "Check constraint 'ck_bookmark_target' is violated.", "23000", 3819);
    }

    /** What {@code SIGNAL SQLSTATE '45000'} becomes: no error number, and not an integrity error. */
    private static SQLException signalledRefusal() {
        return new SQLException("BR-02: you can only bookmark your own tips", "45000");
    }

    // ------------------------------------------------------------------
    //  The dedupe key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A duplicate key on the dedupe constraint is recognised through Spring's wrapper")
    void duplicateKeyIsRecognised() {
        // DataIntegrityViolationException -> SQLException, which is how Hibernate/JPA hands it over.
        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                duplicateKey());

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isTrue();
    }

    @Test
    @DisplayName("Another integrity violation is not reported as a duplicate bookmark")
    void otherIntegrityViolationsAreNotDuplicates() {
        // Both are DataIntegrityViolationException and both carry SQLSTATE 23000, so a check that
        // stopped at either would call them duplicates. Telling a caller an item is already saved when
        // it is not sends them looking in a list that does not contain it.
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(
                new DataIntegrityViolationException("fk", missingTipForeignKey()))).isFalse();

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(
                new DataIntegrityViolationException("check", targetCheckViolation()))).isFalse();
    }

    @Test
    @DisplayName("The constraint is found even when the driver's exception is nested deeper")
    void nestingDepthDoesNotHideTheConstraint() {
        // Hibernate sometimes wraps more than once; the cause chain is walked rather than a fixed
        // depth being assumed.
        Throwable failure = new DataIntegrityViolationException("outer",
                new IllegalStateException("middle", duplicateKey()));

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  Trigger refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {
        // This is the case a type check alone would miss. Spring translates 45000 to
        // InvalidDataAccessResourceUsageException, not to DataIntegrityViolationException, so code
        // that caught the latter would let the refusal escape as a 500. It is also the case a
        // message-text check must not be used for: the trigger can be reworded without the API
        // contract changing.
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal());

        assertThat(BookmarkWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isFalse();
    }

    @Test
    @DisplayName("The same refusal is recognised when its message is not the trigger's current text")
    void theTriggersProseIsNotWhatIsMatched() {
        // A reworded trigger is still the same rule. Keyed on SQLSTATE, the classifier survives it.
        Throwable reworded = new InvalidDataAccessResourceUsageException("could not execute statement",
                new SQLException("some future wording of the same rule", "45000"));

        assertThat(BookmarkWriteFailure.isSignalledRefusal(reworded)).isTrue();
    }

    @Test
    @DisplayName("A duplicate key is not mistaken for a trigger refusal")
    void duplicateKeyIsNotASignalledRefusal() {
        Throwable failure = new DataIntegrityViolationException("dup", duplicateKey());

        assertThat(BookmarkWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(BookmarkWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  Ordering and the unrecognised case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The three recognisers are distinguishable, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {
        Throwable duplicate = new DataIntegrityViolationException("dup", duplicateKey());
        Throwable signalled = new InvalidDataAccessResourceUsageException("sig", signalledRefusal());
        Throwable foreignKey = new DataIntegrityViolationException("fk", missingTipForeignKey());

        // A duplicate key: the dedupe rule, and also a generic constraint violation - which is why the
        // service checks the dedupe branch first and would otherwise lose the precise answer.
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(duplicate)).isTrue();
        assertThat(BookmarkWriteFailure.isConstraintViolation(duplicate)).isTrue();
        assertThat(BookmarkWriteFailure.isSignalledRefusal(duplicate)).isFalse();

        // A SIGNAL: only the trigger rule.
        assertThat(BookmarkWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(signalled)).isFalse();
        assertThat(BookmarkWriteFailure.isConstraintViolation(signalled)).isFalse();

        // A missing tip: the generic constraint case, and neither of the two above.
        assertThat(BookmarkWriteFailure.isConstraintViolation(foreignKey)).isTrue();
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(foreignKey)).isFalse();
        assertThat(BookmarkWriteFailure.isSignalledRefusal(foreignKey)).isFalse();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreNotClaimed() {
        // If every failure matched a rule, the service's final rethrow would be dead code and a
        // genuine fault would be reported as a business rule the caller could act on.
        Throwable failure = new IllegalStateException("connection reset");

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isFalse();
        assertThat(BookmarkWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(BookmarkWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {
        // The driver does not always populate a message, and a contains() on null would throw inside
        // the translation - turning a refusal into a 500.
        Throwable failure = new DataIntegrityViolationException("no message",
                new SQLException((String) null, "23000"));

        assertThat(BookmarkWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isFalse();
        assertThat(BookmarkWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    @Test
    @DisplayName("A nulled cause chain does not break the classification")
    void aMissingCauseIsSurvivable() {
        Throwable failure = new DataIntegrityViolationException("no cause");

        assertThat(BookmarkWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isFalse();
        assertThat(BookmarkWriteFailure.isSignalledRefusal(failure)).isFalse();
    }
}
