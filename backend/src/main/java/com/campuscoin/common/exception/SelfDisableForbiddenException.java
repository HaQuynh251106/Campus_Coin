package com.campuscoin.common.exception;

/**
 * UC-22 B3: an administrator tried to disable their own account, and the system refuses it.
 *
 * <p>The rule exists so an installation cannot lock itself out through one mistake: with a single
 * administrator, disabling that account leaves nobody able to enable it again, because every
 * administrative endpoint - including this one - requires an active administrator.
 *
 * <p><b>The refusal is answered by the service, not by the database, and the reason is testable.</b>
 * {@code sp_set_user_status} signals SQLSTATE 45000 for all three of its refusals - the account does
 * not exist, the status is invalid, and this one - so the exception alone cannot say which fired.
 * The service checks this case before calling, which leaves the procedure's signal meaning only "the
 * target does not exist", classified by a pre-read rather than by matching the procedure's prose. The
 * procedure keeps its own check unchanged; it is the guarantee for a hand-run {@code CALL}.
 *
 * <p><b>The guard is deliberately one-sided.</b> An administrator <em>may</em> re-enable their own
 * account: {@code ACTIVE} on an already-active account is a permitted no-op, and the procedure's
 * condition is {@code p_actor_id = p_target_user_id AND p_new_status = 'DISABLED'}. That asymmetry is
 * not an oversight, and a reviewer should not "fix" it - disabling is the direction that can lock the
 * system out, so disabling is the only direction that needs refusing.
 *
 * <p>A conflict rather than a validation error: the {@code status} the caller sent is perfectly
 * valid, it just cannot be applied to this account by this caller. No audit row is written, because
 * nothing happened.
 */
public class SelfDisableForbiddenException extends ApiException {

    public SelfDisableForbiddenException(String message) {
        super(ErrorCode.SELF_DISABLE_FORBIDDEN, message);
    }
}
