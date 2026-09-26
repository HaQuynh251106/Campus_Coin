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

/**
 * Reads and writes the student's own learned mappings - {@code category_rules}, UC-08.
 *
 * <p><b>This is the table the "reuse the existing category-rule design" instruction points at.</b>
 * The schema anticipated this module: the table's comment above it records that UC-08's "learn from
 * corrections" is implemented as a per-student keyword-to-category mapping, and that the business note
 * explicitly does not require training a machine-learning model. So nothing new is created here. There
 * is exactly one table, its unique key defines what "the same rule" means, and both statements below
 * are built on that key.
 *
 * <p><b>Why the write is a plain statement and not a procedure.</b> Every other write in this project
 * goes through a stored procedure, and the reason is that the procedures carry rules Java must not
 * restate. There is no procedure for this table - the audit of the schema found none, and none was
 * added, because inventing one would be adding a route to a table no procedure has ever known - so the
 * statement is written here. What the schema still enforces without help is the part that matters:
 * {@code uk_rule_user_keyword} makes "one rule per student, keyword and mode" true rather than assumed,
 * {@code fk_rule_category} makes the category exist, {@code fk_rule_user} gives the row an owner, and
 * {@code ck_rule_confidence} bounds the confidence. Nothing below could write a rule the schema does
 * not describe.
 *
 * <p><b>{@code userId} is never anything but the caller's own.</b> It reaches this class from the
 * verified bearer token through {@code CategorisationService}, so there is no parameter anywhere in
 * this module through which a caller could name another student; the read narrows on it, the write sets
 * it, and the projection does not even carry it back. The rule rows are the student's own corrections,
 * which is the most personal data in the table.
 *
 * <p>Native queries rather than a mapped entity, for the reason {@code AnomalyViewDao} gives: a
 * projection cannot be flushed, so no JPA write can compete with the upsert over the unique key.
 */
@Repository
public class CategoryRuleDao {

    /**
     * UC-08: every mapping the caller has taught the system.
     *
     * <p>Bounded by nothing but the student's own account, and that is a decision rather than an
     * omission for the same reason {@code AnomalyViewDao#findScanCandidates} is unbounded: nothing here
     * is a page. The answer is a lookup table consulted once per suggestion, and a student who has filed
     * a few hundred distinct descriptions has a few hundred rows - which is what indexing them on
     * {@code (user_id, keyword)} is for, and what the matcher reads in one pass.
     *
     * <p>Order is not specified and is not needed. {@code CategoryRuleMatcher} imposes its own
     * precedence - an exact match before a substring one, and the longest keyword between two substring
     * matches - and it derives that from the rows, not from the order they arrived in. Ordering here
     * would suggest a precedence the matcher does not honour.
     */
    private static final String SELECT_RULES = """
            SELECT r.id         AS ruleId,
                   r.keyword    AS keyword,
                   r.match_mode AS matchMode,
                   r.category_id AS categoryId,
                   r.confidence AS confidence
              FROM category_rules r
             WHERE r.user_id = :userId
            """;

    /**
     * UC-08 B6: teaches the system the mapping the student just filed, or corrects one it already knew.
     *
     * <p><b>The upsert is on {@code uk_rule_user_keyword} - {@code (user_id, keyword, match_mode)}.</b>
     * That is what makes a correction a correction rather than a second, competing rule: filing
     * "Campus Cafe" under a different category the second time updates the row the first filing created,
     * so the next suggestion follows the student and there is never a pair of rules disagreeing about
     * one description.
     *
     * <p><b>{@code category_id}, {@code type} and {@code source} are the three columns a re-filing
     * changes</b>, and they are the three that describe the mapping: which category, of which type, and
     * whether it came from a correction. {@code confidence} and {@code hit_count} are left alone -
     * nothing reads them (see {@code CategoryRuleRow}) and, on an insert, both take the defaults the
     * schema declares.
     *
     * <p><b>The alias form of {@code ON DUPLICATE KEY UPDATE} is used, not {@code VALUES()}.</b> MySQL
     * deprecated {@code VALUES(column)} in 8.0.20, and the supported replacement is to alias the
     * incoming row and refer to it by alias - which is what {@code AS incoming} below does. The
     * stored-procedure precedent for this statement, {@code sp_generate_monthly_insight}'s
     * {@code ON DUPLICATE KEY UPDATE} on {@code insights}, also avoids {@code VALUES()}, but for a
     * reason this class does not have: inside a stored program the values being inserted are already
     * named local variables, so it refers to those directly. Java has no such variables, so the alias
     * is how a native statement gets the same, non-deprecated shape.
     *
     * <p>Parameters are bound by name and never interpolated. {@code matchMode} is sent as the enum's
     * {@code name()}, which is the member the column's {@code ENUM} stores - never its ordinal, whose
     * meaning would change if the constants were ever reordered.
     *
     * @param userId      the caller, whose own rule table this is
     * @param keyword     the normalised description, produced by {@code CategoryRuleMatcher} - the one
     *                    definition of the stored form
     * @param type        the chosen category's own type, which BR-05 makes the record's type too
     * @param source      {@code OVERRIDE} when the student changed what was suggested, {@code ACCEPTED}
     *                    when they did not contradict it (see {@link RuleSource} for why that covers
     *                    "nothing was suggested")
     */
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

    /** UC-08: the caller's own mappings. An empty list is the ordinary case for a new student. */
    @Transactional(readOnly = true)
    public List<CategoryRuleRow> findRules(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_RULES, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(CategoryRuleDao::toRow).toList();
    }

    /**
     * One rule as the projection carries it.
     *
     * <p>{@code match_mode} is resolved by name, so a member the enum does not know about fails here
     * rather than being read as {@code EXACT} and quietly applied with the wrong comparison - the
     * reasoning {@code AnomalyViewDao#toRow} records for {@code flag_type}. The column is
     * {@code NOT NULL} with a default, so a null would mean a hand-run {@code UPDATE}.
     */
    private static CategoryRuleRow toRow(Tuple row) {
        return new CategoryRuleRow(
                ((Number) row.get("ruleId")).longValue(),
                row.get("keyword", String.class),
                RuleMatchMode.valueOf(row.get("matchMode", String.class)),
                ((Number) row.get("categoryId")).longValue(),
                row.get("confidence", java.math.BigDecimal.class));
    }
}
