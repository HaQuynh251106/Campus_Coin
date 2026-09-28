package com.campuscoin.recent.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.recent.entity.RecentAction;

@Repository
public class RecentActivityProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void touch(Long userId, Long transactionId, RecentAction action) {
        entityManager.createNativeQuery(
                        "CALL sp_touch_recent_activity(:userId, :transactionId, :action)")
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId)
                .setParameter("action", action.name())
                .executeUpdate();
    }
}
