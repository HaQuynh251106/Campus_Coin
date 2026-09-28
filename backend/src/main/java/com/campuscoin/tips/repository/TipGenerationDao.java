package com.campuscoin.tips.repository;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TipGenerationDao {

    private static final String SELECT_ACTIVE_STUDENTS = """
            SELECT CAST(u.id AS SIGNED) AS userId
              FROM users u
             WHERE u.role = 'STUDENT' AND u.status = 'ACTIVE'
             ORDER BY u.id
            """;

    private static final String CALL_GENERATE_TIPS =
            "CALL sp_generate_tips(:userId, :periodMonth, NULL)";

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void generateTips(Long userId, LocalDate periodMonth) {
        callGenerateTips(userId, periodMonth);
    }

    @Transactional(readOnly = true)
    public List<Long> findActiveStudentIds() {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ACTIVE_STUDENTS, Tuple.class)
                .getResultList();

        return rows.stream()
                .map(row -> ((Number) row.get("userId")).longValue())
                .toList();
    }

    private void callGenerateTips(Long userId, LocalDate periodMonth) {
        entityManager.createNativeQuery(CALL_GENERATE_TIPS)
                .setParameter("userId", userId)
                .setParameter("periodMonth", periodMonth)
                .executeUpdate();
    }
}
