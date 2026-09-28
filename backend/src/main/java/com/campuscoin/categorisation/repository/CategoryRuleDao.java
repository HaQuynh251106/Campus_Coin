package com.campuscoin.categorisation.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.entity.RuleMatchMode;
import com.campuscoin.categorisation.entity.RuleSource;
import com.campuscoin.category.entity.CategoryType;

@Repository
public class CategoryRuleDao {

    private static final String SELECT_RULES = """
            SELECT r.id         AS ruleId,
                   r.keyword    AS keyword,
                   r.match_mode AS matchMode,
                   r.category_id AS categoryId,
                   r.confidence AS confidence
              FROM category_rules r
             WHERE r.user_id = :userId
            """;

    @Transactional
    public void upsert(Long userId, String keyword, CategoryType type, Long categoryId,
                       RuleSource source) {
        entityManager.createNativeQuery("""
                        INSERT INTO category_rules (user_id, keyword, match_mode, category_id, type, source)
                        VALUES (:userId, :keyword, :matchMode, :categoryId, :type, :source) AS incoming
                        ON DUPLICATE KEY UPDATE category_id = incoming.category_id,
                                                type        = incoming.type,
                                                source      = incoming.source
                        """)
                .setParameter("userId", userId)
                .setParameter("keyword", keyword)
                .setParameter("matchMode", RuleMatchMode.EXACT.name())
                .setParameter("categoryId", categoryId)
                .setParameter("type", type.name())
                .setParameter("source", source.name())
                .executeUpdate();
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<CategoryRuleRow> findRules(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_RULES, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(CategoryRuleDao::toRow).toList();
    }

    private static CategoryRuleRow toRow(Tuple row) {
        return new CategoryRuleRow(
                ((Number) row.get("ruleId")).longValue(),
                row.get("keyword", String.class),
                RuleMatchMode.valueOf(row.get("matchMode", String.class)),
                ((Number) row.get("categoryId")).longValue(),
                row.get("confidence", java.math.BigDecimal.class));
    }
}
