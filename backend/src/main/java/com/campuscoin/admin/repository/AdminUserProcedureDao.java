package com.campuscoin.admin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.entity.AccountStatus;

/**
 * Calls the procedures that change an account's state (UC-22 B3, B4).
 *
 * <p><b>Both writes go through the database, and neither is a Hibernate {@code UPDATE}.</b>
 * {@code User} is a mapped entity, so {@code user.setStatus(...)} followed by a flush would compile -
 * and would skip {@code sp_require_admin}, skip the session revocation, skip the {@code token_version}
 * bump, and leave no audit row. That is the failure the whole administration module is arranged to
 * prevent, and the reason {@code AccountStatus} has no setter on the entity. The two methods here are
 * the only way this module changes an account.
 *
 * <p>What is <em>not</em> here, deliberately: the procedures' own rules. That the actor is an active
 * administrator, that disabling revokes every open session with {@code revoked_reason =
 * 'ADMIN_DISABLE'}, that it bumps {@code token_version}, and that an administrator cannot disable
 * their own account are all decisions the database makes. Restating any of them in Java would create a
 * second source of truth; the service does pre-read the account and pre-check the self-disable rule,
 * but for a different purpose - to answer "no such account" and "you cannot disable yourself" as their
 * own API errors, which the procedure cannot do because it raises SQLSTATE 45000 for every refusal.
 * The procedure's checks stay as the guarantee for a hand-run {@code CALL}.
 */
@Repository
public class AdminUserProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Enables or disables an account (UC-22 B3).
     *
     * <p>The side effects are asymmetric and both matter. On {@code DISABLED} every open session of
     * the target is revoked and {@code token_version} is incremented, so a JWT already issued to that
     * student stops working immediately rather than at its expiry - which is the entire security
     * meaning of disabling an account (BR-03). On {@code ACTIVE} nothing is revoked: re-enabling
     * restores the ability to sign in and does not resurrect the sessions that were killed.
     *
     * <p>An audit row is written by the procedure, as {@code USER_DISABLED} or {@code USER_ENABLED}.
     *
     * <p>Both parameters are always present, so the named call needs no type declarations. The status
     * is passed as its enum name, which is what the {@code users.status} ENUM stores.
     *
     * <p>Not marked {@code readOnly}: nothing about it is a read, and MySQL refuses any {@code CALL}
     * on a read-only connection in any case.
     *
     * @param targetUserId the account to change, already confirmed to exist
     * @param actorId      the administrator making the change, re-checked by {@code sp_require_admin}
     * @param status       the state to move the account to
     * @param ipAddress    client address, for the audit row
     */
    @Transactional
    public void setStatus(Long targetUserId, Long actorId, AccountStatus status, String ipAddress) {
        entityManager.createNativeQuery(
                        "CALL sp_set_user_status(:targetUserId, :actorId, :status, :ipAddress)")
                .setParameter("targetUserId", targetUserId)
                .setParameter("actorId", actorId)
                .setParameter("status", status.name())
                .setParameter("ipAddress", ipAddress)
                .executeUpdate();
    }

    /**
     * Issues a password reset link for another account and records that it was sent (UC-22 B4).
     *
     * <p><b>The caller supplies the token hash, never a token.</b> This class cannot generate the raw
     * value, because only the hash is ever stored - the service hashes a fresh random token and hands
     * the result here, keeping the plaintext in the layer that also delivers it. That is what makes it
     * impossible for a token to be read back out of the database, and why the response to this
     * operation carries a message rather than a link.
     *
     * <p>The procedure delegates to {@code sp_create_password_reset_token}, so the token's TTL and the
     * invalidation of the account's earlier unused tokens are the same rules the student-initiated flow
     * uses (BR-04). The difference between the two paths is who may ask, not what happens.
     *
     * <p>{@code sp_admin_send_password_reset} does <em>not</em> check that the target account exists -
     * an unknown id passes straight through to {@code sp_create_password_reset_token} and is refused
     * only by {@code fk_prt_user}, arriving here as an integrity violation rather than as a
     * {@code SIGNAL}. The service therefore reads the account first, which it has to do anyway to
     * obtain the address the link is sent to, and the foreign key is classified as a fallback.
     *
     * <p>Audit row: {@code PASSWORD_RESET_SENT}, target entity {@code users}.
     *
     * @param targetUserId the account the link is for, already confirmed to exist
     * @param actorId      the administrator sending it, re-checked by {@code sp_require_admin}
     * @param tokenHash    SHA-256 hex of the raw token; only this value reaches the database
     * @param ipAddress    client address, for the audit row
     */
    @Transactional
    public void sendPasswordReset(Long targetUserId, Long actorId, String tokenHash, String ipAddress) {
        entityManager.createNativeQuery(
                        "CALL sp_admin_send_password_reset(:targetUserId, :actorId, :tokenHash, :ipAddress)")
                .setParameter("targetUserId", targetUserId)
                .setParameter("actorId", actorId)
                .setParameter("tokenHash", tokenHash)
                .setParameter("ipAddress", ipAddress)
                .executeUpdate();
    }
}
