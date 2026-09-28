package com.campuscoin.imports.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ImportProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void applyBatch(Long batchId) {
        entityManager.createNativeQuery("CALL sp_apply_csv_batch(:batchId)")
                .setParameter("batchId", batchId)
                .executeUpdate();
    }
}
