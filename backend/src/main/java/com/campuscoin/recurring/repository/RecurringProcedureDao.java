package com.campuscoin.recurring.repository;

import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RecurringProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void postDueOccurrences(LocalDate asOf) {
        entityManager.createNativeQuery("CALL sp_post_recurring_transactions(:asOf)")
                .setParameter("asOf", asOf)
                .executeUpdate();
    }
}
