package com.campuscoin.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The complete set of error codes the API can return.
 *
 * <p>Each constant carries the HTTP status it maps to, so a handler never has to guess.
 * The code (not the message) is the stable part of the contract: Angular switches on
 * {@code errorCode}, while {@code message} is human-readable text that may be reworded.
 */
public enum ErrorCode {

    /** Bean Validation rejected the request body. UC-01 A2, UC-02 invalid request. */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),

    /** Malformed or missing request body, wrong types, unreadable JSON. */
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),

    /** Request parameters failed a constraint that is not a body field error. */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),

    /** The password reset token is unknown, already used, or expired. BR-04, UC-03 A1. */
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST),

    /** The supplied credentials do not match any account, or the password is wrong. UC-02 A1. */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),

    /** No token was supplied, or it is malformed, tampered with, or expired. UC-02 A3, E1. */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),

    /** The account exists but is DISABLED. UC-02 A2, BR-03. */
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED),

    /** Too many failed sign-in attempts or reset requests. Section 7.10 hardening. */
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS),

    /** Authenticated, but the account role is not allowed to use this endpoint. UC-05 A1, E1. */
    ACCESS_DENIED(HttpStatus.FORBIDDEN),

    /** The email address is already registered. UC-01 A1, UAT-01, BR-01. */
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),

    /**
     * The category name is already taken. UC-06.
     *
     * <p>Two situations share this code because they are one condition from the caller's point of
     * view - pick a different name: the student already has a category of that name and type
     * ({@code uk_categories_scope_type_name}), or a shared default category already uses it
     * ({@code trg_categories_before_insert}). The message distinguishes them, the code does not,
     * because the client's remedy is the same.
     */
    CATEGORY_NAME_TAKEN(HttpStatus.CONFLICT),

    /**
     * The category is referenced by a transaction, budget or recurring rule, so the change would
     * rewrite history. UC-06, BR-05, BR-07.
     *
     * <p>Raised when the database refuses a type change ({@code trg_categories_before_update}) or
     * a delete ({@code trg_categories_before_delete}). The API does not decide this itself: the
     * rule spans three tables owned by later modules, and the trigger is where it holds for every
     * caller.
     */
    CATEGORY_IN_USE(HttpStatus.CONFLICT),

    /**
     * The transaction is already in the trash, so there is nothing left to delete. UC-10, BR-09.
     *
     * <p>Reported rather than swallowed because {@code sp_soft_delete_transaction} refuses it: the
     * stored procedure is the authority on a transaction's lifecycle, and answering "deleted"
     * without having called it would be the API disagreeing with the database.
     */
    TRANSACTION_ALREADY_DELETED(HttpStatus.CONFLICT),

    /**
     * The transaction is not in the trash, so there is nothing to restore. UC-10 A1.
     *
     * <p>An application-side guard: {@code sp_restore_transaction} would accept a live row and
     * change nothing. Answering "restored" for a request that did nothing would tell the client
     * something happened that did not, so the state is checked before the call.
     */
    TRANSACTION_NOT_DELETED(HttpStatus.CONFLICT),

    /**
     * The recurring rule has already generated transactions, so it cannot be deleted - end it
     * instead. UC-09.
     *
     * <p>Reported rather than allowed because {@code transactions.recurring_rule_id} has no foreign
     * key, so nothing in the database would stop the delete; the transactions the rule generated
     * would be left pointing at a row that no longer exists, and
     * {@code sp_validate_transaction} would then refuse every later edit, soft delete and restore of
     * them. See {@link RecurringRuleInUseException}.
     */
    RECURRING_RULE_IN_USE(HttpStatus.CONFLICT),

    /**
     * The recurring rule's category has been retired, and the database refuses every change to the
     * rule while that is so - including pausing or ending it. UC-09, BR-07.
     *
     * <p>A conflict rather than a field error: the caller sent nothing wrong. It is a state the
     * rule cannot be modified in, and retiring a category is what creates it. See
     * {@link CategoryRetiredException}.
     */
    CATEGORY_RETIRED(HttpStatus.CONFLICT),

    /**
     * The recurring rule has already ended, and {@code ENDED} is terminal - it is not a state a
     * rule can be moved back out of. UC-09.
     *
     * <p>A conflict rather than a field error: the value the caller sent is a valid status, it just
     * does not apply to this rule's current state. The remedy is to create a new rule. See
     * {@link RecurringRuleEndedException}.
     */
    RECURRING_RULE_ENDED(HttpStatus.CONFLICT),

    /**
     * A spending limit already exists for this category and month, and BR-11 allows at most one.
     * UC-13.
     *
     * <p>{@code uk_budget_user_cat_month} is the guarantee - {@code (user_id, category_id,
     * period_month)} is exactly BR-11 - and this code exists so the collision is answered with
     * something the caller can act on. Setting a limit the student already set is not an error worth
     * a database message; it is an edit, and the remedy is the update endpoint. See
     * {@link BudgetAlreadyExistsException}.
     */
    BUDGET_ALREADY_EXISTS(HttpStatus.CONFLICT),

    /**
     * The item is already saved, so there is nothing to bookmark. UC-19 B1.
     *
     * <p>{@code uk_bookmark_dedupe} is the guarantee - its {@code dedupe_key} is
     * {@code user_id|item_type|tip_id}, so the same tip cannot be saved twice by one student. This code
     * exists so the collision is answered with something the caller can act on: the item is already in
     * their list, and the remedies are to read that list or to edit the note on the entry already
     * there. See {@link BookmarkAlreadyExistsException}.
     */
    BOOKMARK_ALREADY_EXISTS(HttpStatus.CONFLICT),

    /**
     * An administrator asked to disable their own account, and the system refuses it. UC-22 B3.
     *
     * <p>Not a field error - the status the caller sent is a valid one - and not a generic conflict,
     * because the remedy is specific: another administrator has to do it. The database refuses this
     * too ({@code sp_set_user_status} signals it), but the service answers first, because that
     * procedure raises SQLSTATE 45000 for all three of its refusals and this is the only one the
     * caller can distinguish by their own input. See {@link SelfDisableForbiddenException}.
     */
    SELF_DISABLE_FORBIDDEN(HttpStatus.CONFLICT),

    /**
     * The setting exists but is not one this API may change. UC-23, VĐ-05.
     *
     * <p>{@code system_settings} holds sixteen rows and {@code sp_admin_set_threshold} permits six of
     * them. The rest are set for the deployment, are read by Java rather than by that procedure, or
     * belong to a capability this build does not have - so a client that offered to edit one would be
     * offering an edit the database refuses. {@code GET /api/v1/admin/settings} marks each row
     * {@code adjustable} using the same list, so this code answers a request that ignored the flag.
     * See {@link ThresholdNotAdjustableException}.
     */
    THRESHOLD_NOT_ADJUSTABLE(HttpStatus.CONFLICT),

    /**
     * A saving-tip template already uses this code. UC-21 B3.
     *
     * <p>{@code uk_tip_template_code} is the guarantee - a template's code is its identity, and
     * {@code sp_generate_tips} and the seeded data both refer to templates by it. The collision is
     * answered with something the caller can act on: choose another code, or edit the template that
     * already has this one. See {@link TipTemplateCodeTakenException}.
     */
    TIP_TEMPLATE_CODE_TAKEN(HttpStatus.CONFLICT),

    /**
     * A tip template's {@code code} was sent with a different value, and a code cannot be changed.
     * UC-21 B4.
     *
     * <p><b>Reported rather than ignored, because ignoring it is what the database would do.</b>
     * {@code sp_admin_upsert_tip_template}'s update branch writes every editable column and does not
     * write {@code code} at all, so a {@code PATCH} carrying a new one would succeed, change nothing,
     * and tell the client "saved" - leaving them with a code that never moved. The service compares
     * the stored code with the one sent and answers here instead. Sending the stored code unchanged is
     * accepted, so a client can round-trip a full representation.
     * See {@link TipTemplateCodeImmutableException}.
     */
    TIP_TEMPLATE_CODE_IMMUTABLE(HttpStatus.CONFLICT),

    /** The requested resource does not exist. */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /** A database rule rejected the operation. The raw SQL message is never forwarded. */
    DATA_CONFLICT(HttpStatus.CONFLICT),

    /** Unexpected failure. The cause is logged server-side and never sent to the client. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
