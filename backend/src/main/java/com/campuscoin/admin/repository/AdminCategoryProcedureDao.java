package com.campuscoin.admin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.StoredProcedureQuery;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.category.entity.CategoryType;

@Repository
public class AdminCategoryProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void upsertDefaultCategory(Long actorId,
                                      Long categoryId,
                                      String name,
                                      CategoryType type,
                                      String icon,
                                      String color,
                                      String description,
                                      Integer sortOrder,
                                      Boolean isActive,
                                      String ipAddress) {
        StoredProcedureQuery query = entityManager.createStoredProcedureQuery(
                "sp_admin_upsert_default_category");

        query.registerStoredProcedureParameter(1, Long.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(2, Long.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(3, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(4, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(5, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(6, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(7, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(8, Short.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(9, Boolean.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(10, String.class, ParameterMode.IN);

        query.setParameter(1, actorId);
        query.setParameter(2, categoryId);
        query.setParameter(3, name);
        query.setParameter(4, type == null ? null : type.name());
        query.setParameter(5, icon);
        query.setParameter(6, color);
        query.setParameter(7, description);
        query.setParameter(8, sortOrder == null ? null : sortOrder.shortValue());
        query.setParameter(9, isActive);
        query.setParameter(10, ipAddress);

        query.executeUpdate();
    }
}
