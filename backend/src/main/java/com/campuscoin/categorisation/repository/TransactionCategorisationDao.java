package com.campuscoin.categorisation.repository;

import java.math.BigDecimal;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.categorisation.entity.TransactionCategorisationRow;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.common.jdbc.JdbcValues;

@Repository
public class TransactionCategorisationDao {

    private static final String SELECT_ONE = """
            SELECT t.category_id             AS categoryId,
                   c.type                    AS categoryType,
                   t.description             AS encryptedDescription,
                   t.ai_suggested_category_id AS aiSuggestedCategoryId,
                   t.ai_confidence           AS aiConfidence,
                   t.ai_overridden           AS aiOverridden
              FROM transactions t
              JOIN categories c ON c.id = t.category_id
             WHERE t.id = :transactionId
               AND t.user_id = :userId
               AND t.is_deleted = 0
            """;

    private static final String UPDATE_SUGGESTION = """
            UPDATE transactions
               SET ai_suggested_category_id = :suggestedCategoryId,
                   ai_confidence            = :confidence,
                   ai_overridden            = :overridden
             WHERE id = :transactionId
               AND user_id = :userId
               AND is_deleted = 0
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<TransactionCategorisationRow> find(Long userId, Long transactionId) {
        @SuppressWarnings("unchecked")
        java.util.List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId)
                .getResultList();

        return rows.stream().map(TransactionCategorisationDao::toRow).findFirst();
    }

    @Transactional
    public int updateSuggestion(Long userId, Long transactionId, Long suggestedCategoryId,
                                BigDecimal confidence, boolean overridden) {
        return entityManager.createNativeQuery(UPDATE_SUGGESTION)
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId)
                .setParameter("suggestedCategoryId", suggestedCategoryId)
                .setParameter("confidence", confidence)
                .setParameter("overridden", overridden)
                .executeUpdate();
    }

    private static TransactionCategorisationRow toRow(Tuple row) {
        Boolean overridden = JdbcValues.toBoolean(row.get("aiOverridden"));

        return new TransactionCategorisationRow(
                ((Number) row.get("categoryId")).longValue(),
                CategoryType.valueOf(row.get("categoryType", String.class)),
                row.get("encryptedDescription", String.class),
                toLong(row.get("aiSuggestedCategoryId")),
                row.get("aiConfidence", BigDecimal.class),
                overridden != null && overridden);
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
