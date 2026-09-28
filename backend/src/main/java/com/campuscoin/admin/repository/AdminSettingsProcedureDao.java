package com.campuscoin.admin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AdminSettingsProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void setThreshold(Long actorId, String key, String value, String ipAddress) {
        entityManager.createNativeQuery(
                        "CALL sp_admin_set_threshold(:actorId, :key, :value, :ipAddress)")
                .setParameter("actorId", actorId)
                .setParameter("key", key)
                .setParameter("value", value)
                .setParameter("ipAddress", ipAddress)
                .executeUpdate();
    }
}
