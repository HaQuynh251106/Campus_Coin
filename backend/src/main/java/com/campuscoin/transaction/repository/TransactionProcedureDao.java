package com.campuscoin.transaction.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.transaction.entity.Transaction;

@Repository
public class TransactionProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void softDelete(Long transactionId, Long userId) {
        entityManager.createNativeQuery("CALL sp_soft_delete_transaction(:id, :userId)")
                .setParameter("id", transactionId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Transactional
    public void restore(Transaction transaction, Long userId) {
        entityManager.createNativeQuery("CALL sp_restore_transaction(:id, :userId)")
                .setParameter("id", transaction.getId())
                .setParameter("userId", userId)
                .executeUpdate();

        entityManager.refresh(transaction);
    }
}
