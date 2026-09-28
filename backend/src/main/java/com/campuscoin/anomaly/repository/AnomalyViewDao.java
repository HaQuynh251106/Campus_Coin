package com.campuscoin.anomaly.repository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.anomaly.entity.AnomalyFlagType;
import com.campuscoin.anomaly.entity.CategoryAmountStats;
import com.campuscoin.anomaly.entity.FlaggedTransactionRow;
import com.campuscoin.category.entity.CategoryType;

@Repository
public class AnomalyViewDao {

    private static final String SELECT_FLAGGED = """
            SELECT t.id           AS transactionId,
                   t.category_id  AS categoryId,
                   c.name         AS categoryName,
                   c.type         AS categoryType,
                   t.amount       AS amount,
                   t.txn_date     AS txnDate,
                   t.description  AS encryptedDescription,
                   t.is_flagged   AS isFlagged,
                   t.flag_type    AS flagType,
                   t.flag_note    AS flagNote
              FROM transactions t
              JOIN categories c ON c.id = t.category_id
             WHERE t.user_id = :userId
               AND t.is_deleted = 0
               AND t.is_flagged = 1
             ORDER BY t.txn_date DESC, t.id DESC
             LIMIT :limit
            """;

    private static final String SELECT_SCAN_CANDIDATES = """
            SELECT t.id           AS transactionId,
                   t.category_id  AS categoryId,
                   c.name         AS categoryName,
                   c.type         AS categoryType,
                   t.amount       AS amount,
                   t.txn_date     AS txnDate,
                   t.description  AS encryptedDescription,
                   t.is_flagged   AS isFlagged,
                   t.flag_type    AS flagType,
                   t.flag_note    AS flagNote
              FROM transactions t
              JOIN categories c ON c.id = t.category_id
             WHERE t.user_id = :userId
               AND t.is_deleted = 0
             ORDER BY t.id
            """;

    private static final String SELECT_CATEGORY_STATS = """
            SELECT t.category_id AS categoryId,
                   COUNT(*)      AS recordCount,
                   SUM(t.amount) AS totalAmount
              FROM transactions t
             WHERE t.user_id = :userId
               AND t.is_deleted = 0
             GROUP BY t.category_id
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<FlaggedTransactionRow> findFlagged(Long userId, int limit) {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_FLAGGED, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .getResultList();

        return rows.stream().map(AnomalyViewDao::toRow).toList();
    }

    @Transactional(readOnly = true)
    public List<FlaggedTransactionRow> findScanCandidates(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_SCAN_CANDIDATES, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(AnomalyViewDao::toRow).toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, CategoryAmountStats> findCategoryStats(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_CATEGORY_STATS, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        Map<Long, CategoryAmountStats> stats = new HashMap<>();
        for (Tuple row : rows) {
            Long categoryId = ((Number) row.get("categoryId")).longValue();
            stats.put(categoryId, new CategoryAmountStats(
                    categoryId,
                    ((Number) row.get("recordCount")).longValue(),
                    row.get("totalAmount", BigDecimal.class)));
        }
        return stats;
    }

    private static FlaggedTransactionRow toRow(Tuple row) {
        return new FlaggedTransactionRow(
                ((Number) row.get("transactionId")).longValue(),
                ((Number) row.get("categoryId")).longValue(),
                row.get("categoryName", String.class),
                CategoryType.valueOf(row.get("categoryType", String.class)),
                row.get("amount", BigDecimal.class),
                row.get("txnDate", java.sql.Date.class).toLocalDate(),
                row.get("encryptedDescription", String.class),
                isFlagged(row.get("isFlagged")),
                AnomalyFlagType.valueOf(row.get("flagType", String.class)),
                row.get("flagNote", String.class));
    }

    private static boolean isFlagged(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && ((Number) value).intValue() != 0;
    }
}
