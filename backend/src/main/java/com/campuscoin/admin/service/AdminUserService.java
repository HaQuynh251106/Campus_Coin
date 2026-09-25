package com.campuscoin.admin.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.dto.AdminPasswordResetResponse;
import com.campuscoin.admin.dto.AdminUserResponse;
import com.campuscoin.admin.dto.SetUserStatusRequest;
import com.campuscoin.admin.entity.AdminUserRow;
import com.campuscoin.admin.mapper.AdminUserMapper;
import com.campuscoin.admin.repository.AdminUserProcedureDao;
import com.campuscoin.admin.repository.AdminUserViewDao;
import com.campuscoin.auth.entity.AccountStatus;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.auth.security.PasswordResetLinkBuilder;
import com.campuscoin.auth.security.PasswordResetNotifier;
import com.campuscoin.auth.security.TokenHashService;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.SelfDisableForbiddenException;

/**
 * Managing the accounts on the system (UC-22).
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>That the caller may act at all</b> - {@code sp_require_admin} re-derives the actor's role
 *       and status from {@code users} inside the write. This class never checks
 *       {@code principal.isAdmin()}: the security filter chain has already established the role for
 *       the request, and the procedure re-establishes it for the write, so a third check here would be
 *       a copy able to drift rather than a guarantee.</li>
 *   <li><b>That disabling revokes every open session and bumps {@code token_version}</b> - it is the
 *       second half of {@code sp_set_user_status}, and it is why a student's existing JWT stops working
 *       the instant they are disabled (BR-03) rather than at its expiry. This class does not touch
 *       {@code user_sessions} or {@code token_version}.</li>
 *   <li><b>The reset token's TTL, one-time use and the invalidation of earlier tokens</b> -
 *       {@code sp_admin_send_password_reset} delegates to {@code sp_create_password_reset_token}, the
 *       same procedure the student-initiated flow uses (BR-04).</li>
 *   <li><b>Every audit row</b> - the two procedures write {@code USER_DISABLED}/{@code USER_ENABLED}
 *       and {@code PASSWORD_RESET_SENT} themselves.</li>
 * </ul>
 *
 * <p><b>What is genuinely this class's.</b> Refusing a self-disable before the call so it can be
 * reported as itself, hashing the reset token and handing the plaintext only to the notifier, and
 * describing the account with the columns UC-22 publishes.
 *
 * <p><b>There is no user id parameter for "who is calling".</b> The actor comes from the verified token
 * through {@link AuthenticatedUser}, and the path id is only ever the account being acted on. A client
 * cannot claim to be another administrator.
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /**
     * UC-22 B4's answer. It names the mechanism rather than confirming delivery, because a send can
     * fail without the operation failing - the token is already issued - and an administrator who
     * needs to know whether it arrived has the student in front of them. The raw link is never
     * returned.
     */
    static final String RESET_SENT_MESSAGE =
            "A password reset link has been sent to the account's email address.";

    /**
     * What both write refusals on this path say.
     *
     * <p>One message for both branches because the caller's remedy is one action - reload the account
     * and try again - and the two branches differ only in which fact changed underneath the request.
     * The database's own text is never forwarded.
     */
    private static final String RELOAD_AND_RETRY =
            "The account could not be changed. Reload it and try again.";

    private final AdminUserViewDao userViewDao;
    private final AdminUserProcedureDao userProcedureDao;
    private final AdminUserMapper userMapper;
    private final TokenHashService tokenHashService;
    private final PasswordResetNotifier notifier;
    private final PasswordResetLinkBuilder linkBuilder;

    public AdminUserService(AdminUserViewDao userViewDao,
                            AdminUserProcedureDao userProcedureDao,
                            AdminUserMapper userMapper,
                            TokenHashService tokenHashService,
                            PasswordResetNotifier notifier,
                            PasswordResetLinkBuilder linkBuilder) {
        this.userViewDao = userViewDao;
        this.userProcedureDao = userProcedureDao;
        this.userMapper = userMapper;
        this.tokenHashService = tokenHashService;
        this.notifier = notifier;
        this.linkBuilder = linkBuilder;
    }

    /**
     * UC-22 B1: every account on the system.
     *
     * <p>{@code readOnly = true} documents that listing changes nothing. The list is never empty
     * against the real schema - the seeded administrator is always present - but an empty list would
     * still be a real answer about the data rather than a missing resource, so it is not a
     * {@code 404}.
     */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> list() {
        return userMapper.toResponses(userViewDao.findAll());
    }

    /**
     * UC-22 B3: enable or disable an account.
     *
     * <p><b>The self-disable refusal is decided here, before the call, and that is not a duplicate of
     * the procedure's check.</b> {@code sp_set_user_status} raises SQLSTATE 45000 for all three of its
     * refusals - the account does not exist, the status is invalid, and this one - so a refusal reaching
     * this method could not be told apart from "no such account" without matching the procedure's prose,
     * which this project forbids. Checking the one case the caller can see in their own request leaves
     * the surviving 45000 meaning exactly "the target does not exist", classified by the pre-read.
     * The procedure keeps its own check unchanged; it is the guarantee for a hand-run {@code CALL}.
     *
     * <p><b>The guard is one-sided on purpose.</b> An administrator may re-enable their own account -
     * the procedure's condition is {@code p_actor_id = p_target_user_id AND p_new_status = 'DISABLED'} -
     * because disabling is the direction that can lock an installation out of itself. A reviewer should
     * not "fix" the asymmetry: an administrator setting their own account {@code ACTIVE} when it is
     * already active is a permitted no-op and writes an audit row saying so, which is a truthful record
     * of what was asked for.
     *
     * <p>The response is read back through the same projection the list uses, so the caller sees the
     * account in its new state - and, after a disable, with nothing else changed that they did not ask
     * for.
     *
     * @param ipAddress client address, recorded on the audit row
     * @throws NotFoundException             no account has this id
     * @throws SelfDisableForbiddenException the caller is the target and asked to disable it
     */
    @Transactional
    public AdminUserResponse setStatus(AuthenticatedUser actor, Long targetUserId,
                                       SetUserStatusRequest request, String ipAddress) {
        // Read first, so a missing account is its own answer rather than a 45000 that means three
        // things. The row also supplies nothing else - the status comes from the request.
        AdminUserRow target = loadAccount(targetUserId);

        if (target.id().equals(actor.userId()) && request.status() == AccountStatus.DISABLED) {
            log.info("Self-disable refused adminUserId={}", actor.userId());
            throw new SelfDisableForbiddenException(
                    "You cannot disable your own account. Ask another administrator to do it.");
        }

        try {
            userProcedureDao.setStatus(targetUserId, actor.userId(), request.status(), ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, targetUserId);
        }

        log.info("Account status changed actorId={} targetUserId={} status={}",
                actor.userId(), targetUserId, request.status());

        return userMapper.toResponse(loadForResponse(targetUserId));
    }

    /**
     * UC-22 B4: send a password reset link to another account (VĐ-06).
     *
     * <p><b>An administrator can only send a link; nobody's data is deleted or replaced.</b> VĐ-06 is
     * explicit that an administrator does not set or see a password - the student chooses a new one
     * through the same screen the self-service flow uses. So this method generates a token, stores only
     * its hash, and hands the plaintext to the notifier, exactly as {@code PasswordResetService} does.
     * Nothing in the path can return the link.
     *
     * <p><b>The link is built by the shared {@link PasswordResetLinkBuilder}</b>, so an
     * administrator-triggered reset produces a link the student's own flow would accept. If each flow
     * built its own, a deployment that changed one base URL would silently break the other.
     *
     * <p><b>The account is read before the call, for two reasons.</b> The address is needed to send the
     * link, and {@code sp_admin_send_password_reset} does not check that the target exists - an unknown
     * id would reach {@code fk_prt_user} and arrive as an integrity violation. Reading first turns that
     * into a {@code 404} the caller can act on, and the foreign key becomes a fallback that only fires
     * if the row is removed between the read and the write.
     *
     * <p>Answers {@code 202} rather than {@code 200} because the work is a side effect - an email - and
     * not a resource this endpoint created or returned.
     *
     * @param ipAddress client address, recorded on the audit row
     * @throws NotFoundException no account has this id
     */
    @Transactional
    public AdminPasswordResetResponse sendPasswordReset(AuthenticatedUser actor, Long targetUserId,
                                                        String ipAddress) {
        AdminUserRow target = loadAccount(targetUserId);

        String rawToken = tokenHashService.newSecretToken();

        try {
            userProcedureDao.sendPasswordReset(
                    targetUserId,
                    actor.userId(),
                    tokenHashService.sha256Hex(rawToken),
                    ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, targetUserId);
        }

        // Only the hash is stored; the plaintext goes to the account's mailbox and nowhere else. The
        // link is deliberately not logged, and neither is the token (section 7.6).
        notifier.sendPasswordResetLink(target.email(), linkBuilder.build(rawToken));

        log.info("Administrator sent a password reset actorId={} targetUserId={}",
                actor.userId(), targetUserId);

        return new AdminPasswordResetResponse(RESET_SENT_MESSAGE);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /** One account, or the {@code 404} that says there is none. */
    private AdminUserRow loadAccount(Long userId) {
        return userViewDao.findOne(userId)
                .orElseThrow(() -> new NotFoundException("User account not found."));
    }

    /**
     * The account just written, read back so the response describes the stored row.
     *
     * <p>The row was read a moment ago in the same transaction, so an empty result here means it was
     * removed in between - and a request cannot widen that window, because the account was
     * {@code ACTIVE} enough to pass {@code sp_require_admin}'s own lookup inside the write. A fault
     * rather than a missing resource, hence {@code IllegalStateException} and a server error rather
     * than a {@code 404} that would blame the caller's request for it.
     */
    private AdminUserRow loadForResponse(Long userId) {
        return userViewDao.findOne(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "User " + userId + " was not readable back after being written."));
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>Which rule fired is decided by {@link AdminWriteFailure}, which asks by SQLSTATE and by
     * constraint name rather than by matching the driver's message. On the status path the self-disable
     * case is already answered above, so a surviving {@code 45000} can only be the procedure's own
     * lookup refusing the <em>actor</em> - a role or status this request cannot see - or the target row
     * disappearing. On the reset path it can only be the actor, because the target was read first.
     * Both are answered as a conflict rather than mislabelled as a {@code 404} the caller would try to
     * fix by changing the id.
     *
     * <p>Anything unrecognised is rethrown unchanged, so {@code GlobalExceptionHandler} answers it as a
     * generic conflict or an internal error rather than this method guessing.
     */
    private RuntimeException translateWriteFailure(RuntimeException ex, Long targetUserId) {
        if (AdminWriteFailure.isSignalledRefusal(ex)) {
            log.info("Account write rejected by a procedure signal targetUserId={}", targetUserId);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        if (AdminWriteFailure.isConstraintViolation(ex)) {
            // The only constraint reachable here is a foreign key on the target or the actor, which
            // means the row was removed between the read and the write.
            log.info("Account write rejected by a constraint targetUserId={}", targetUserId);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        log.error("Account write failed unexpectedly targetUserId={}", targetUserId, ex);
        return ex;
    }
}
