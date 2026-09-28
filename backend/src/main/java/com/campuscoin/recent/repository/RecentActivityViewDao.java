package com.campuscoin.recent.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.recent.entity.RecentAction;
import com.campuscoin.recent.entity.RecentActivityRow;

@Repository
public class RecentActivityViewDao {

    private static final String SELECT_RECENT = """
            SELECT v.transaction_id AS transactionId,
                   v.action         AS action,
                   v.occurred_at    AS occurredAt,
                   v.category_id    AS categoryId,
                   v.type           AS categoryType,
                   v.amount         AS amount,
                   v.description    AS encryptedDescription,
                   v.txn_date       AS txnDate
              FROM v_user_recent_activity v
             WHERE v.user_id = :userId
             ORDER BY v.occurred_at DESC, v.transaction_id DESC, v.action DESC
             LIMIT :limit
            """;

    private static final String SELECT_ONE = """
            SELECT v.transaction_id AS transactionId,
                   v.action         AS action,
                   v.occurred_at    AS occurredAt,
                   v.category_id    AS categoryId,
                   v.type           AS categoryType,
                   v.amount         AS amount,
                   v.description    AS encryptedDescription,
                   v.txn_date       AS txnDate
              FROM v_user_recent_activity v
             WHERE v.user_id = :userId
               AND v.transaction_id = :transactionId
               AND v.action = :action
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<RecentActivityRow> findRecent(Long userId, int limit) {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_RECENT, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .getResultList();

        return rows.stream().map(RecentActivityViewDao::toRow).toList();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<RecentActivityRow> findOne(Long userId, Long transactionId,
                                                         RecentAction action) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId)
                .setParameter("action", action.name())
                .getResultList();

        return rows.stream().map(RecentActivityViewDao::toRow).findFirst();
    }

    private static RecentActivityRow toRow(Tuple row) {
        return new RecentActivityRow(
                ((Number) row.get("transactionId")).longValue(),
                RecentAction.valueOf(row.get("action", String.class)),
                toLocalDateTime(row.get("occurredAt", java.sql.Timestamp.class)),
                row.get("categoryId") == null ? null : ((Number) row.get("categoryId")).longValue(),
                CategoryType.valueOf(row.get("categoryType", String.class)),
                row.get("amount", java.math.BigDecimal.class),
                row.get("encryptedDescription", String.class),
                row.get("txnDate", java.sql.Date.class).toLocalDate());
    }

    private static java.time.LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
