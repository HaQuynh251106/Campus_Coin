package com.campuscoin.anomaly.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.anomaly.entity.AnomalyFlagType;

@Repository
public class AnomalyFlagProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void flag(Long userId, Long transactionId, AnomalyFlagType flagType, String flagNote) {
        entityManager.createNativeQuery(
                        "CALL sp_flag_transaction(:transactionId, :userId, :flagType, :flagNote)")
                .setParameter("transactionId", transactionId)
                .setParameter("userId", userId)
                .setParameter("flagType", flagType.name())
                .setParameter("flagNote", flagNote)
                .executeUpdate();
    }
}
