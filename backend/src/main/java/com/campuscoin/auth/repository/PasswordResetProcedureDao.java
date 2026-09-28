package com.campuscoin.auth.repository;

import java.sql.SQLException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.StoredProcedureQuery;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.common.exception.InvalidResetTokenException;

@Repository
public class PasswordResetProcedureDao {

    private static final String SIGNAL_SQLSTATE = "45000";

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void createResetToken(Long userId, String tokenHash, String ipAddress) {
        entityManager.createNativeQuery(
                        "CALL sp_create_password_reset_token(:userId, :tokenHash, :ipAddress)")
                .setParameter("userId", userId)
                .setParameter("tokenHash", tokenHash)
                .setParameter("ipAddress", ipAddress)
                .executeUpdate();
    }

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
