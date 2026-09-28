package com.campuscoin.admin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.StoredProcedureQuery;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.entity.TipConditionType;

@Repository
public class AdminTipTemplateProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void upsertTipTemplate(Long actorId,
                                  Long templateId,
                                  String code,
                                  TipConditionType conditionType,
                                  String titleTemplate,
                                  String bodyTemplate,
                                  Integer defaultPriority,
                                  Boolean isActive,
                                  String ipAddress) {
        StoredProcedureQuery query = entityManager.createStoredProcedureQuery(
                "sp_admin_upsert_tip_template");

        query.registerStoredProcedureParameter(1, Long.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(2, Long.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(3, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(4, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(5, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(6, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(7, Short.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(8, Boolean.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(9, String.class, ParameterMode.IN);

        query.setParameter(1, actorId);
        query.setParameter(2, templateId);
        query.setParameter(3, code);
        query.setParameter(4, conditionType == null ? null : conditionType.name());
        query.setParameter(5, titleTemplate);
        query.setParameter(6, bodyTemplate);
        query.setParameter(7, defaultPriority == null ? null : defaultPriority.shortValue());
        query.setParameter(8, isActive);
        query.setParameter(9, ipAddress);

        query.executeUpdate();
    }
}
