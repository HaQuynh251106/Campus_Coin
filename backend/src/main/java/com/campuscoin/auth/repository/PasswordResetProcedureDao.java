package com.campuscoin.auth.repository;

import java.sql.SQLException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.StoredProcedureQuery;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.common.exception.InvalidResetTokenException;

/**
 * Calls the three stored procedures that own the password reset token lifecycle (UC-03, BR-04).
 *
 * <p>This class deliberately contains no reset rules of its own. Expiry, one-time use and the
 * atomic consume-and-validate step all live in the database, and re-stating them in Java would
 * create a second source of truth that could drift from the one the rest of the system uses.
 * The methods here pass arguments in and translate the database's refusal into an API error.
 *
 * <p>There is no {@code PasswordResetToken} entity either: nothing in the application ever reads
 * or writes {@code password_reset_tokens} directly, so mapping the table would be dead weight.
 */
@Repository
public class PasswordResetProcedureDao {

    /** The SQLSTATE {@code sp_complete_password_reset} raises when a token is unusable. */
    private static final String SIGNAL_SQLSTATE = "45000";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Issues a new reset token for an account (UC-03 B2).
     *
     * <p>The procedure also invalidates the account's earlier unused tokens, so requesting a
     * second link immediately retires the first - the requirement asks for one usable link at a
     * time. The TTL is read by the procedure from {@code auth.reset_token_ttl_minutes}, so the
     * caller does not pass one.
     *
     * @param userId    the account the link is for
     * @param tokenHash SHA-256 hex of the raw token; only this value is stored
     * @param ipAddress client address, for the audit column
     */
    @Transactional
    public void createResetToken(Long userId, String tokenHash, String ipAddress) {
        entityManager.createNativeQuery(
                        "CALL sp_create_password_reset_token(:userId, :tokenHash, :ipAddress)")
                .setParameter("userId", userId)
                .setParameter("tokenHash", tokenHash)
                .setParameter("ipAddress", ipAddress)
                .executeUpdate();
    }

    /**
     * Read-only pre-check used to decide whether the "choose a new password" screen may open
     * (UC-03 B5).
     *
     * <p>It does not consume the token - consumption happens atomically in
     * {@link #completeReset} - so a student who opens the link and abandons the form can use it
     * again until it expires.
     *
     * <p>Not marked {@code readOnly}: MySQL classifies any {@code CALL} as a statement that may
     * write, so a read-only connection refuses it outright with "Connection is read-only" even
     * though this procedure only selects. The procedure's own body is the guarantee that nothing
     * is modified - it reads and returns, and consumption happens only in
     * {@link #completeReset}.
     *
     * @return the account the token belongs to, or {@code null} when the token is unknown,
     *         already used, or expired
     */
    @Transactional
    public Long findUserIdByValidToken(String tokenHash) {
        StoredProcedureQuery query = entityManager
                .createStoredProcedureQuery("sp_verify_password_reset_token")
                .registerStoredProcedureParameter(1, String.class, ParameterMode.IN)
                .registerStoredProcedureParameter(2, Long.class, ParameterMode.OUT)
                .setParameter(1, tokenHash);

        query.execute();
        Object userId = query.getOutputParameterValue(2);
        return userId == null ? null : ((Number) userId).longValue();
    }

    /**
     * Consumes the token and sets the new password (UC-03 B7, BR-04).
     *
     * <p>The procedure validates and consumes the token in a single {@code UPDATE}, so two
     * requests arriving together cannot both succeed; a later step of the same call bumps
     * {@code token_version} and revokes every open session of the account, which is why the
     * caller must run this inside a transaction.
     *
     * @param tokenHash       SHA-256 hex of the raw token presented by the client
     * @param newPasswordHash bcrypt hash of the new password, produced before the call
     * @throws InvalidResetTokenException when the database refuses the token
     */
    @Transactional
    public void completeReset(String tokenHash, String newPasswordHash) {
        try {
            entityManager.createNativeQuery(
                            "CALL sp_complete_password_reset(:tokenHash, :passwordHash, @reset_user_id)")
                    .setParameter("tokenHash", tokenHash)
                    .setParameter("passwordHash", newPasswordHash)
                    .executeUpdate();
        } catch (RuntimeException ex) {
            if (isSignalledRefusal(ex)) {
                throw new InvalidResetTokenException(ex);
            }
            throw ex;
        }
    }

    /**
     * The procedure signals SQLSTATE 45000 for an invalid, used or expired token. The driver
     * error code (1644) is not portable enough to match on, so the SQLSTATE is checked instead,
     * walking the cause chain because the persistence layer wraps the {@link SQLException}.
     */
    private boolean isSignalledRefusal(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && SIGNAL_SQLSTATE.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
