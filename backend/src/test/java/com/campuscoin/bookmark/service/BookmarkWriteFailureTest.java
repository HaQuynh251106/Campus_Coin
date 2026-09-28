package com.campuscoin.bookmark.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class BookmarkWriteFailureTest {

    private static SQLException duplicateKey() {
        return new SQLException(
                "Duplicate entry '2-TIP-12-0' for key 'bookmarks.uk_bookmark_dedupe'",
                "23000", 1062);
    }

    private static SQLException missingTipForeignKey() {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`bookmarks`, CONSTRAINT `fk_bookmark_tip` FOREIGN KEY "
                        + "(`tip_id`) REFERENCES `user_tips` (`id`) ON DELETE CASCADE)",
                "23000", 1452);
    }

    private static SQLException targetCheckViolation() {
        return new SQLException(
                "Check constraint 'ck_bookmark_target' is violated.", "23000", 3819);
    }

    private static SQLException signalledRefusal() {
        return new SQLException("BR-02: you can only bookmark your own tips", "45000");
    }

    @Test
    @DisplayName("A duplicate key on the dedupe constraint is recognised through Spring's wrapper")
    void duplicateKeyIsRecognised() {

        Throwable failure = new DataIntegrityViolationException("could not execute statement",
                duplicateKey());

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isTrue();
    }

    @Test
    @DisplayName("Another integrity violation is not reported as a duplicate bookmark")
    void otherIntegrityViolationsAreNotDuplicates() {

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(
                new DataIntegrityViolationException("fk", missingTipForeignKey()))).isFalse();

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(
                new DataIntegrityViolationException("check", targetCheckViolation()))).isFalse();
    }

    @Test
    @DisplayName("The constraint is found even when the driver's exception is nested deeper")
    void nestingDepthDoesNotHideTheConstraint() {

        Throwable failure = new DataIntegrityViolationException("outer",
                new IllegalStateException("middle", duplicateKey()));

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isTrue();
    }

    @Test
    @DisplayName("A SIGNAL refusal is recognised as 45000, not as an integrity violation")
    void signalledRefusalIsRecognised() {

        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal());

        assertThat(BookmarkWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isFalse();
    }

    @Test
    @DisplayName("The same refusal is recognised when its message is not the trigger's current text")
    void theTriggersProseIsNotWhatIsMatched() {

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

    @Test
    @DisplayName("The three recognisers are distinguishable, so the order in the service decides")
    void eachRefusalMatchesOnlyItsOwnRule() {
        Throwable duplicate = new DataIntegrityViolationException("dup", duplicateKey());
        Throwable signalled = new InvalidDataAccessResourceUsageException("sig", signalledRefusal());
        Throwable foreignKey = new DataIntegrityViolationException("fk", missingTipForeignKey());

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(duplicate)).isTrue();
        assertThat(BookmarkWriteFailure.isConstraintViolation(duplicate)).isTrue();
        assertThat(BookmarkWriteFailure.isSignalledRefusal(duplicate)).isFalse();

        assertThat(BookmarkWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(signalled)).isFalse();
        assertThat(BookmarkWriteFailure.isConstraintViolation(signalled)).isFalse();

        assertThat(BookmarkWriteFailure.isConstraintViolation(foreignKey)).isTrue();
        assertThat(BookmarkWriteFailure.isDuplicateBookmark(foreignKey)).isFalse();
        assertThat(BookmarkWriteFailure.isSignalledRefusal(foreignKey)).isFalse();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreNotClaimed() {

        Throwable failure = new IllegalStateException("connection reset");

        assertThat(BookmarkWriteFailure.isDuplicateBookmark(failure)).isFalse();
        assertThat(BookmarkWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(BookmarkWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {

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
