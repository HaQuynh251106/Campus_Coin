package com.campuscoin.admin.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Recognises which database rule refused an administrative write.
 *
 * <p>A separate type from the services for the reason {@code BookmarkWriteFailure} and
 * {@code TransactionWriteFailure} are: the classification is the part that has to be right, and
 * inside a service method it could not be tested on its own. The exception shapes this recognises
 * are the driver's, not this application's, so a test can only pin them by constructing them
 * directly - which means the logic has to live somewhere a test can reach without a database.
 *
 * <p><b>The rules are asked about by SQLSTATE and by the constraint's own name, never by the
 * driver's message text.</b> A message is not a contract and is localised; the SQLSTATE is standard,
 * and {@code uk_tip_template_code} is the schema's identifier, appearing untranslated in every
 * locale. The {@code SIGNAL} prose the eight admin procedures raise is deliberately <em>not</em>
 * matched either - the procedures and this class are two halves of one project, but a procedure can
 * be reworded without the API contract changing, and a classifier keyed on its wording would break
 * silently when it was.
 *
 * <p><b>What {@link #isSignalledRefusal} means depends on the path that asked.</b> SQLSTATE 45000 is
 * what every admin procedure raises for every refusal, so the exception alone does not say which
 * rule fired. Each caller narrows it by pre-checking everything it can see in Java first, which
 * leaves exactly one refusal reachable on that path:
 *
 * <ul>
 *   <li>the status path pre-checks the self-disable rule, so 45000 there means the target account
 *       does not exist;</li>
 *   <li>the announcement toggle pre-reads the row, so 45000 means the announcement does not exist;</li>
 *   <li>the default-category update pre-reads the row, so 45000 means no default category has that
 *       id - the procedure's own BR-06 refusal, which its wording confirms is the only one left;</li>
 *   <li>the tip-template update pre-reads the row, so 45000 means the template does not exist.</li>
 * </ul>
 *
 * <p>That is the same arrangement {@code TransactionService} uses for "already deleted": rather than
 * matching prose, arrange the checks so the prose is unambiguous. The procedures keep their own
 * checks unchanged - they are the guarantee for a hand-run {@code CALL} - and this class simply does
 * not depend on their wording.
 *
 * <p>The remaining branch that is genuinely indistinguishable - {@code sp_admin_send_password_reset}
 * reaching a foreign key because the target id does not exist - is answered by the caller pre-reading
 * the account, and its foreign key is classified here as an integrity violation rather than being
 * mistaken for a duplicate.
 *
 * <p>Nothing here logs. The caller decides what is worth recording, and the exception is deliberately
 * not written out because its message carries the constraint name and the offending key.
 */
final class AdminWriteFailure {

    /**
     * The SQLSTATE MySQL raises for a trigger or procedure that refuses with {@code SIGNAL}.
     *
     * <p>Spring does not translate this into a {@code DataIntegrityViolationException}: it arrives as
     * {@code InvalidDataAccessResourceUsageException}, because MySQL reports "resource usage" rather
     * than an integrity error. Asking by SQLSTATE rather than by exception type is what makes the
     * refusal recognisable whichever wrapper the persistence layer chose.
     */
    private static final String SIGNAL_SQLSTATE = "45000";

    /** The SQLSTATE of an integrity constraint violation, including a restricting foreign key. */
    private static final String CONSTRAINT_SQLSTATE = "23000";

    /**
     * The unique key on {@code tip_templates.code}.
     *
     * <p>Recognised by name rather than by SQLSTATE alone, because 23000 covers every integrity
     * violation - and on this path that includes {@code fk_tip_tpl_created_by} and, on the category
     * path, {@code fk_categories_created_by}. A violation naming that foreign key is "the creating
     * administrator no longer exists", not "that code is taken", and reporting it as a duplicate
     * would send the caller to fix the wrong field.
     */
    private static final String TIP_TEMPLATE_CODE_CONSTRAINT = "uk_tip_template_code";

    /**
     * The unique key on {@code (scope_key, type, name)} that keeps default and personal categories
     * from sharing a name.
     *
     * <p>BR-06's requirement, enforced for a default category by this unique key rather than by a
     * trigger: {@code trg_categories_before_insert}'s duplicate check applies only to
     * {@code user_id IS NOT NULL}, so an administrator creating a default row is refused by the
     * index. That is worth knowing here because it is the only duplicate the admin path can produce,
     * and it is why the same {@code CATEGORY_NAME_TAKEN} answer as the student module gives is the
     * right one - the caller's remedy is identical, whatever refused it.
     */
    private static final String CATEGORY_SCOPE_NAME_CONSTRAINT = "uk_categories_scope_type_name";

    private AdminWriteFailure() {
    }

    /**
     * A procedure or trigger refused the write with {@code SIGNAL SQLSTATE '45000'}.
     *
     * <p>Asked first by every caller, because it is the branch whose meaning the caller has to supply.
     * Anything on SQLSTATE 23000 is a constraint doing its job; anything on 45000 is a rule one of the
     * eight admin procedures or the category triggers decided to enforce in code.
     */
    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    /**
     * {@code uk_tip_template_code} refused the insert: a template with that code already exists.
     *
     * <p>Asked before {@link #isConstraintViolation}, which would otherwise swallow it - every
     * duplicate is also an integrity violation, and the specific answer is the useful one.
     *
     * <p>Recognised by SQLSTATE plus the constraint's name rather than by Spring's exception type.
     * Both are needed: the name alone would match a message that happened to quote it, and the
     * SQLSTATE alone covers every integrity violation on this path - the foreign key on the acting
     * administrator's own id among them, which is "that account is gone", not "that code is taken".
     * The SQLSTATE is what the database reports and the name is the schema's own identifier, so
     * neither depends on the driver's wording or the locale.
     */
    static boolean isDuplicateTipTemplateCode(Throwable failure) {
        return hasSqlState(failure, CONSTRAINT_SQLSTATE)
                && mentions(failure, TIP_TEMPLATE_CODE_CONSTRAINT);
    }

    /**
     * {@code uk_categories_scope_type_name} refused the insert: a default category of that name and
     * type already exists.
     *
     * <p>Asked before {@link #isConstraintViolation}, and by SQLSTATE plus name for the reason above.
     * The name is what distinguishes it from the other 23000 on this path, which is
     * {@code fk_categories_created_by} - the acting administrator's account having been removed, which
     * is a different problem with a different remedy.
     */
    static boolean isDuplicateDefaultCategory(Throwable failure) {
        return hasSqlState(failure, CONSTRAINT_SQLSTATE)
                && mentions(failure, CATEGORY_SCOPE_NAME_CONSTRAINT);
    }

    /**
     * An integrity violation that is none of the named duplicates: a foreign key or a CHECK.
     *
     * <p>Asked last of the 23000 branches. On the create paths this can only be a foreign key on the
     * actor's own id - {@code fk_categories_created_by} or {@code fk_tip_tpl_created_by} - which
     * means the administrator account was removed between the request arriving and the write landing,
     * a window of milliseconds that no request can widen. Classified rather than left to surface as a
     * server error, so if it ever does fire it is answered as a conflict.
     *
     * <p>Reaches the reset path as {@code fk_prt_user} when the target id does not exist, which the
     * service pre-empts by reading the account first; this is the fallback behind that read.
     */
    static boolean isConstraintViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                || hasSqlState(failure, CONSTRAINT_SQLSTATE);
    }

    /** Walks the cause chain: the SQLSTATE is on the driver's exception, not on Spring's wrapper. */
    private static boolean hasSqlState(Throwable failure, String sqlState) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && Objects.equals(sqlState, sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentions(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
