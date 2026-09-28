package com.campuscoin.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class AdminWriteFailureTest {

    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    private static SQLException duplicateKey(String constraint) {
        return new SQLException(
                "Duplicate entry 'OVER_BUDGET' for key 'tip_templates." + constraint + "'",
                "23000", 1062);
    }

    private static SQLException restrictingForeignKey(String constraint, String table) {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`" + table + "`, CONSTRAINT `" + constraint + "` "
                        + "FOREIGN KEY (`created_by`) REFERENCES `users` (`id`))",
                "23000", 1451);
    }

    @Test
    @DisplayName("A procedure's SIGNAL is recognised even though Spring calls it resource usage")
    void signalledRefusalIsRecognised() {
        Throwable failure = new InvalidDataAccessResourceUsageException("could not execute statement",
                signalledRefusal("BR-06: administrator privileges required (role ADMIN and status ACTIVE)"));

        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(AdminWriteFailure.isConstraintViolation(failure)).isFalse();
    }

    @Test
    @DisplayName("Every refusal text the eight procedures raise is recognised through the same branch")
    void everySignalledRefusalIsRecognised() {

        String[] messages = {
                "BR-06: administrator privileges required (role ADMIN and status ACTIVE)",
                "Account does not exist",
                "Invalid status",
                "An administrator cannot disable their own account",
                "Missing name, or the category type is not valid",
                "BR-06: default category to update was not found",
                "Title and body must not be empty",
                "Announcement does not exist",
                "Missing tip template code, title or body",
                "Tip template does not exist",
                "This configuration key cannot be changed through this procedure",
                "Baseline month count must be a whole number from 1 to 12",
                "Threshold must be a positive number",
                "Configuration key does not exist"
        };

        for (String message : messages) {
            Throwable failure = new InvalidDataAccessResourceUsageException("statement",
                    signalledRefusal(message));
            assertThat(AdminWriteFailure.isSignalledRefusal(failure))
                    .as("%s must be recognised as a refusal", message)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("A refusal is found even when the driver's exception is nested deeper")
    void nestingDepthDoesNotHideTheRefusal() {

        Throwable failure = new InvalidDataAccessResourceUsageException("outer",
                new IllegalStateException("middle",
                        signalledRefusal("Tip template does not exist")));

        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    @Test
    @DisplayName("A duplicate tip-template code is recognised by its index, not by a wrapper type")
    void duplicateTipTemplateCodeIsRecognised() {
        Throwable wrapped = new DataIntegrityViolationException("dup",
                duplicateKey("uk_tip_template_code"));
        Throwable bare = new IllegalStateException("boom",
                duplicateKey("uk_tip_template_code"));

        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(wrapped)).isTrue();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(bare)).isTrue();
    }

    @Test
    @DisplayName("A duplicate default category is recognised by its unique key")
    void duplicateDefaultCategoryIsRecognised() {
        Throwable failure = new DataIntegrityViolationException("dup",
                duplicateKey("uk_categories_scope_type_name"));

        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(failure)).isTrue();
    }

    @Test
    @DisplayName("The acting administrator's foreign key is not mistaken for a duplicate")
    void theActorsForeignKeyIsNotADuplicate() {

        Throwable template = new DataIntegrityViolationException("fk",
                restrictingForeignKey("fk_tip_tpl_created_by", "tip_templates"));
        Throwable category = new DataIntegrityViolationException("fk",
                restrictingForeignKey("fk_categories_created_by", "categories"));

        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(template)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(template)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(category)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(category)).isFalse();

        assertThat(AdminWriteFailure.isConstraintViolation(template)).isTrue();
        assertThat(AdminWriteFailure.isConstraintViolation(category)).isTrue();
    }

    @Test
    @DisplayName("Each duplicate is recognised only by its own constraint's name")
    void theTwoDuplicatesDoNotOverlap() {

        Throwable templateDuplicate = new DataIntegrityViolationException("dup",
                duplicateKey("uk_tip_template_code"));
        Throwable categoryDuplicate = new DataIntegrityViolationException("dup",
                duplicateKey("uk_categories_scope_type_name"));

        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(templateDuplicate)).isTrue();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(templateDuplicate)).isFalse();

        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(categoryDuplicate)).isTrue();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(categoryDuplicate)).isFalse();
    }

    @Test
    @DisplayName("A duplicate is also a constraint violation, so the service must ask about it first")
    void aDuplicateIsAlsoAGenericConstraintViolation() {

        Throwable failure = new DataIntegrityViolationException("dup",
                duplicateKey("uk_tip_template_code"));

        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(failure)).isTrue();
        assertThat(AdminWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("A CHECK constraint is a constraint violation without being a duplicate")
    void checkConstraintsAreRecognisedWithoutBeingDuplicates() {

        Throwable failure = new DataIntegrityViolationException("check",
                new SQLException("Check constraint 'ck_ann_window' is violated.", "HY000", 3819));

        assertThat(AdminWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(failure)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(failure)).isFalse();
    }

    @Test
    @DisplayName("A constraint is recognised from the SQLSTATE alone, without Spring's wrapper type")
    void constraintIsFoundBySqlState() {
        Throwable failure = new IllegalStateException("boom",
                new SQLException("Referencing row", "23000", 1452));

        assertThat(AdminWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    @Test
    @DisplayName("The reset path's foreign key is a constraint violation, not a duplicate")
    void theResetForeignKeysAreNotDuplicates() {

        Throwable failure = new DataIntegrityViolationException("fk",
                new SQLException(
                        "Cannot add or update a child row: a foreign key constraint fails "
                                + "(`campuscoin`.`password_reset_tokens`, CONSTRAINT `fk_prt_user` "
                                + "FOREIGN KEY (`user_id`) REFERENCES `users` (`id`))",
                        "23000", 1451));

        assertThat(AdminWriteFailure.isConstraintViolation(failure)).isTrue();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(failure)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(failure)).isFalse();
        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isFalse();
    }

    @Test
    @DisplayName("A signal and a constraint never match the same branch, so the order decides")
    void aSignalIsNeverAlsoAConstraint() {
        Throwable signalled = new InvalidDataAccessResourceUsageException("sig",
                signalledRefusal("Announcement does not exist"));
        Throwable constraint = new DataIntegrityViolationException("dup",
                duplicateKey("uk_tip_template_code"));

        assertThat(AdminWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(AdminWriteFailure.isConstraintViolation(signalled)).isFalse();

        assertThat(AdminWriteFailure.isSignalledRefusal(constraint)).isFalse();
        assertThat(AdminWriteFailure.isConstraintViolation(constraint)).isTrue();
    }

    @Test
    @DisplayName("Something unrelated is left unrecognised rather than mislabelled")
    void unrelatedFailuresAreNotClaimed() {

        Throwable failure = new IllegalStateException("connection reset");

        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(AdminWriteFailure.isConstraintViolation(failure)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(failure)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(failure)).isFalse();
    }

    @Test
    @DisplayName("A message that merely quotes a constraint name is not a duplicate")
    void aLogShapedMessageIsNotADuplicate() {

        Throwable logShaped = new InvalidDataAccessResourceUsageException(
                "could not execute statement; SQL [CALL sp_admin_upsert_tip_template(?)]; "
                        + "the statement mentioned uk_tip_template_code but the server said "
                        + "nothing about integrity");

        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(logShaped)).isFalse();

        Throwable genuine = new DataIntegrityViolationException("dup",
                duplicateKey("uk_tip_template_code"));
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(genuine)).isTrue();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {

        Throwable signalled = new InvalidDataAccessResourceUsageException("no message",
                new SQLException((String) null, "45000"));
        Throwable noSqlState = new DataIntegrityViolationException(null,
                new SQLException((String) null, (String) null));

        assertThat(AdminWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(AdminWriteFailure.isConstraintViolation(signalled)).isFalse();

        assertThat(AdminWriteFailure.isConstraintViolation(noSqlState)).isTrue();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(noSqlState)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(noSqlState)).isFalse();
    }

    @Test
    @DisplayName("A chain that loops back on itself terminates")
    void aSelfReferencingChainTerminates() {

        SQLException driver = new SQLException("loop", "45000", 1644);
        Throwable failure = new InvalidDataAccessResourceUsageException("outer", driver);

        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isTrue();
    }
}
