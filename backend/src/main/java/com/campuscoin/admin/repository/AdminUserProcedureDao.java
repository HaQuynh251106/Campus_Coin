package com.campuscoin.admin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.entity.AccountStatus;

@Repository
public class AdminUserProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

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
