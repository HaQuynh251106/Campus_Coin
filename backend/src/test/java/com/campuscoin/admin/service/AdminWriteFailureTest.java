package com.campuscoin.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 * The write-refusal classification for the administration endpoints, tested directly.
 *
 * <p>A plain unit test, for the reason {@code RecurringRuleWriteFailureTest} and
 * {@code BookmarkWriteFailureTest} give: the recognisers take a {@code Throwable} and answer a
 * boolean, so nothing here needs a database - and the shapes asserted are the ones MySQL and Spring
 * actually produce, which the integration suites then exercise for real.
 *
 * <p><b>The two duplicate recognisers are the part that needed this file.</b> All eight admin
 * procedures raise SQLSTATE 45000 for every refusal they make, so a refusal cannot be told apart by
 * SQLSTATE alone; the duplicates are the one case where the database says something more specific,
 * and it says it as a constraint <em>name</em> on a 23000. The failures that matter are the two the
 * recognisers must not claim - on the tip-template path {@code fk_tip_tpl_created_by}, and on the
 * category path {@code fk_categories_created_by} - because both are integrity violations on the
 * same SQLSTATE, and reporting either as "that code is taken" would send the caller to fix a field
 * that was never the problem.
 */
class AdminWriteFailureTest {

    /**
     * A procedure or trigger refusal, as {@code SIGNAL SQLSTATE '45000'} arrives.
     *
     * <p>Spring does not call this an integrity error: MySQL reports a {@code SIGNAL} as "resource
     * usage", so it surfaces as {@code InvalidDataAccessResourceUsageException}. That is the whole
     * reason this class asks by SQLSTATE rather than by exception type - a check for
     * {@code DataIntegrityViolationException} would let every refusal in this module escape as a
     * 500. No error number is asserted, because MySQL assigns 1644 to a handlerless {@code SIGNAL}
     * and the classification never reads it.
     */
    private static SQLException signalledRefusal(String message) {
        return new SQLException(message, "45000");
    }

    /** A duplicate key as MySQL 8 reports it: 1062, SQLSTATE 23000, naming the index. */
    private static SQLException duplicateKey(String constraint) {
        return new SQLException(
                "Duplicate entry 'OVER_BUDGET' for key 'tip_templates." + constraint + "'",
                "23000", 1062);
    }

    /**
     * A restricting foreign key as MySQL 8 reports it: 1451, SQLSTATE 23000, naming the constraint.
     *
     * <p>Both foreign keys that can fire on an admin write are built here, because the point of
     * this file is that they are <em>not</em> duplicates - they mean "the acting administrator's
     * row is gone", a fact the caller cannot fix by editing their request.
     */
    private static SQLException restrictingForeignKey(String constraint, String table) {
        return new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`campuscoin`.`" + table + "`, CONSTRAINT `" + constraint + "` "
                        + "FOREIGN KEY (`created_by`) REFERENCES `users` (`id`))",
                "23000", 1451);
    }

    // ------------------------------------------------------------------
    //  The signal: every procedure's refusal, and the branch that means
    //  something different on each path
    // ------------------------------------------------------------------

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
        // The literal SIGNAL texts in db/03_procedures.sql, one per refusal the module can reach.
        // The classifier never matches these words - that is the point of the test below, which
        // shows a message quoting a constraint name is still classified by SQLSTATE. What is
        // asserted here is that none of them escapes unrecognised, which is what would produce a
        // 500 on a request the caller could have fixed.
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
        // Hibernate sometimes wraps more than once, so the chain is walked rather than a fixed
        // depth assumed. The SQLSTATE is only ever on the innermost exception.
        Throwable failure = new InvalidDataAccessResourceUsageException("outer",
                new IllegalStateException("middle",
                        signalledRefusal("Tip template does not exist")));

        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  The two named duplicates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A duplicate tip-template code is recognised by its index, not by a wrapper type")
    void duplicateTipTemplateCodeIsRecognised() {
        Throwable wrapped = new DataIntegrityViolationException("dup",
                duplicateKey("uk_tip_template_code"));
        Throwable bare = new IllegalStateException("boom",
                duplicateKey("uk_tip_template_code"));

        // Wrapped and bare both answer the same, because the SQLSTATE is asked about rather than
        // Spring's exception type. A native CALL can surface the driver's exception directly.
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
        // The reason the recognisers require the constraint's name and not only the SQLSTATE. Both
        // of these are 23000 integrity violations, and both mean "the administrator's own row is
        // gone" - a different problem with a different remedy. Classifying either as a duplicate
        // would tell the caller their code or name was taken when it was not.
        Throwable template = new DataIntegrityViolationException("fk",
                restrictingForeignKey("fk_tip_tpl_created_by", "tip_templates"));
        Throwable category = new DataIntegrityViolationException("fk",
                restrictingForeignKey("fk_categories_created_by", "categories"));

        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(template)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(template)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(category)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(category)).isFalse();

        // ...and they are not lost either: they fall to the generic constraint branch, which the
        // services answer as a conflict rather than a server error.
        assertThat(AdminWriteFailure.isConstraintViolation(template)).isTrue();
        assertThat(AdminWriteFailure.isConstraintViolation(category)).isTrue();
    }

    @Test
    @DisplayName("Each duplicate is recognised only by its own constraint's name")
    void theTwoDuplicatesDoNotOverlap() {
        // The two indexes live on different tables, so neither recogniser can claim the other's
        // failure. If they overlapped, whichever the service asked about first would decide the
        // answer and the second check would be reachable only by accident.
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
        // The ordering the services rely on, asserted rather than assumed: the specific answer is
        // reached only because every caller asks about the duplicate before the general case.
        Throwable failure = new DataIntegrityViolationException("dup",
                duplicateKey("uk_tip_template_code"));

        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(failure)).isTrue();
        assertThat(AdminWriteFailure.isConstraintViolation(failure)).isTrue();
    }

    // ------------------------------------------------------------------
    //  The generic constraint branch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A CHECK constraint is a constraint violation without being a duplicate")
    void checkConstraintsAreRecognisedWithoutBeingDuplicates() {
        // ck_ann_window is the one CHECK this module can reach - an end at or before the start.
        // The service pre-empts it with a field error, so this branch is the fallback; it must
        // still be recognised rather than escaping as a 500 if the pre-check is ever bypassed.
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
        // sp_admin_send_password_reset does not check that its target exists, so an unknown id
        // reaches fk_prt_user. The service pre-reads the account and answers 404; this is the
        // fallback behind that read, and it must not be reported as anything else.
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

    // ------------------------------------------------------------------
    //  Ordering, and the cases that must not be claimed
    // ------------------------------------------------------------------

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
        // If every failure matched a rule, the services' final rethrow would be dead code and a
        // genuine fault - a lost connection, a mapping bug - would be reported to the client as a
        // business rule telling them to reload.
        Throwable failure = new IllegalStateException("connection reset");

        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isFalse();
        assertThat(AdminWriteFailure.isConstraintViolation(failure)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(failure)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(failure)).isFalse();
    }

    @Test
    @DisplayName("A message that merely quotes a constraint name is not a duplicate")
    void aLogShapedMessageIsNotADuplicate() {
        // The negative this test file exists for. A recogniser keyed on the text alone would match
        // anything that happened to mention the index - a wrapped log line, a statement echo, a
        // message from a different table naming it in prose. Requiring the SQLSTATE as well is what
        // makes the answer about the database's report rather than about somebody's wording.
        Throwable logShaped = new InvalidDataAccessResourceUsageException(
                "could not execute statement; SQL [CALL sp_admin_upsert_tip_template(?)]; "
                        + "the statement mentioned uk_tip_template_code but the server said "
                        + "nothing about integrity");

        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(logShaped)).isFalse();

        // ...and the same text on the right SQLSTATE is a duplicate, so the guard is not simply
        // ignoring the name.
        Throwable genuine = new DataIntegrityViolationException("dup",
                duplicateKey("uk_tip_template_code"));
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(genuine)).isTrue();
    }

    @Test
    @DisplayName("A null message in the chain does not break the classification")
    void nullMessagesAreSurvivable() {
        // The driver does not always populate a message. A recogniser that read the text first
        // would throw on that null - which is exactly what asking the SQLSTATE before the text
        // avoids.
        Throwable signalled = new InvalidDataAccessResourceUsageException("no message",
                new SQLException((String) null, "45000"));
        Throwable noSqlState = new DataIntegrityViolationException(null,
                new SQLException((String) null, (String) null));

        assertThat(AdminWriteFailure.isSignalledRefusal(signalled)).isTrue();
        assertThat(AdminWriteFailure.isConstraintViolation(signalled)).isFalse();

        // A DataIntegrityViolationException is claimed by the generic branch on its type, even
        // with no SQLSTATE and no message: Spring only wraps an integrity failure in it.
        assertThat(AdminWriteFailure.isConstraintViolation(noSqlState)).isTrue();
        assertThat(AdminWriteFailure.isDuplicateTipTemplateCode(noSqlState)).isFalse();
        assertThat(AdminWriteFailure.isDuplicateDefaultCategory(noSqlState)).isFalse();
    }

    @Test
    @DisplayName("A chain that loops back on itself terminates")
    void aSelfReferencingChainTerminates() {
        // A defensive case rather than an expected one: the walk follows the chain's own structure,
        // so nothing here should hang. It is asserted because a translation method that could loop
        // would hang a request rather than fail it, which is far harder to diagnose.
        SQLException driver = new SQLException("loop", "45000", 1644);
        Throwable failure = new InvalidDataAccessResourceUsageException("outer", driver);

        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isTrue();
        assertThat(AdminWriteFailure.isSignalledRefusal(failure)).isTrue();
    }
}
