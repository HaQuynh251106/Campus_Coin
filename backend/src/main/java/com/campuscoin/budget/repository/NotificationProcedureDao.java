package com.campuscoin.budget.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public int markRead(Long notificationId, Long userId) {
        return entityManager.createNativeQuery("CALL sp_mark_notification_read(:id, :userId)")
                .setParameter("id", notificationId)
                .setParameter("userId", userId)
                .executeUpdate();
    }
}
