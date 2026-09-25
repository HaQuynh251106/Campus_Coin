package com.campuscoin.admin.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.entity.AdminTipTemplateRow;
import com.campuscoin.admin.entity.TipConditionType;

/**
 * Reads saving-tip templates for the administration screen (UC-21).
 *
 * <p><b>{@code condition_params} is not selected.</b> The column is a {@code JSON} blob whose meaning
 * the schema does not document and which nothing in this build reads - {@code sp_generate_tips} decides
 * its conditions from each student's own data, not from this column - so publishing it would expose a
 * value no reader could interpret. It is recorded as a follow-up rather than guessed at; the exclusion
 * is structural, since {@link AdminTipTemplateRow} has no component for it.
 *
 * <p>Projected by alias into a record rather than mapped as an entity, for the reason the announcement
 * and user readers are: nothing in this module changes a template through Hibernate, and a projection
 * leaves no managed instance for a later change to write through.
 */
@Repository
public class AdminTipTemplateViewDao {

    /** UC-21: every template, active or not. Ordered by priority then id, so the list is stable. */
    private static final String SELECT_ALL = """
            SELECT t.id               AS id,
                   t.code             AS code,
                   t.condition_type   AS conditionType,
                   t.title_template   AS titleTemplate,
                   t.body_template    AS bodyTemplate,
                   t.default_priority AS defaultPriority,
                   t.is_active        AS isActive
              FROM tip_templates t
             ORDER BY t.default_priority ASC, t.id ASC
            """;

    /** One template by id, for the update path's pre-read and for its response. */
    private static final String SELECT_ONE = """
            SELECT t.id               AS id,
                   t.code             AS code,
                   t.condition_type   AS conditionType,
                   t.title_template   AS titleTemplate,
                   t.body_template    AS bodyTemplate,
                   t.default_priority AS defaultPriority,
                   t.is_active        AS isActive
              FROM tip_templates t
             WHERE t.id = :id
            """;

    /**
     * One template by its code, which is the table's unique natural key.
     *
     * <p>Serves two callers. The create path checks it to answer "that code is already taken" with a
     * precise error instead of relying on the unique key's refusal; and the read-back after a create
     * uses it, because {@code sp_admin_upsert_tip_template} has no OUT parameter and
     * {@code LAST_INSERT_ID()} after the call reports the procedure's audit row rather than the
     * template. The code is unique by {@code uk_tip_template_code}, so this is an exact lookup.
     */
    private static final String SELECT_BY_CODE = """
            SELECT t.id               AS id,
                   t.code             AS code,
                   t.condition_type   AS conditionType,
                   t.title_template   AS titleTemplate,
                   t.body_template    AS bodyTemplate,
                   t.default_priority AS defaultPriority,
                   t.is_active        AS isActive
              FROM tip_templates t
             WHERE t.code = :code
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Every template, in the order the generators would consider them.
     *
     * <p>Ordered by {@code default_priority} ascending because that is what the column means - a lower
     * number is a more important tip, and the ordering is the same one the administration screen needs
     * to show to make a priority editable with any confidence. {@code id} makes it total, since two
     * templates may share a priority.
     */
    @Transactional(readOnly = true)
    public List<AdminTipTemplateRow> findAll() {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload returns a raw `Query`. Confined to this local
        // declaration - every row is mapped through toTipTemplateRow, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ALL, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminTipTemplateViewDao::toTipTemplateRow).toList();
    }

    /** One template by id, or empty when it does not exist. */
    @Transactional(readOnly = true)
    public Optional<AdminTipTemplateRow> findOne(Long id) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("id", id)
                .getResultList();

        return rows.stream().map(AdminTipTemplateViewDao::toTipTemplateRow).findFirst();
    }

    /** One template by code, or empty when no template uses it. */
    @Transactional(readOnly = true)
    public Optional<AdminTipTemplateRow> findByCode(String code) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_BY_CODE, Tuple.class)
                .setParameter("code", code)
                .getResultList();

        return rows.stream().map(AdminTipTemplateViewDao::toTipTemplateRow).findFirst();
    }

    /**
     * One row as the projection carries it.
     *
     * <p>{@code conditionType} is resolved by name, so a value the enum does not know about fails here
     * rather than being read as some other member. {@code default_priority} is a {@code SMALLINT}, read
     * through {@code Number} so its width is the driver's business rather than this class's;
     * {@code is_active} goes through {@link AdminTupleValues#toBoolean}, which handles the
     * {@code Boolean} the driver actually returns for a {@code TINYINT(1)}.
     */
    private static AdminTipTemplateRow toTipTemplateRow(Tuple row) {
        return new AdminTipTemplateRow(
                ((Number) row.get("id")).longValue(),
                row.get("code", String.class),
                TipConditionType.valueOf(row.get("conditionType", String.class)),
                row.get("titleTemplate", String.class),
                row.get("bodyTemplate", String.class),
                ((Number) row.get("defaultPriority")).intValue(),
                AdminTupleValues.toBoolean(row.get("isActive")));
    }
}
