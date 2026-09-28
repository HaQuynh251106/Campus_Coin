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

@Repository
public class AdminTipTemplateViewDao {

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

    @Transactional(readOnly = true)
    public List<AdminTipTemplateRow> findAll() {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ALL, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminTipTemplateViewDao::toTipTemplateRow).toList();
    }

    @Transactional(readOnly = true)
    public Optional<AdminTipTemplateRow> findOne(Long id) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("id", id)
                .getResultList();

        return rows.stream().map(AdminTipTemplateViewDao::toTipTemplateRow).findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<AdminTipTemplateRow> findByCode(String code) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_BY_CODE, Tuple.class)
                .setParameter("code", code)
                .getResultList();

        return rows.stream().map(AdminTipTemplateViewDao::toTipTemplateRow).findFirst();
    }

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
