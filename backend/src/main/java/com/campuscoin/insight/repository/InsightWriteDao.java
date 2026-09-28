package com.campuscoin.insight.repository;

import java.sql.Date;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.insight.entity.InsightGeneratedBy;

@Repository
public class InsightWriteDao {

    private static final String UPDATE_AI_NARRATIVE = """
            UPDATE insights
               SET summary_text = :summaryText,
                   advice_text  = :adviceText,
                   generated_by = :generatedBy,
                   model_name   = :modelName
             WHERE user_id = :userId
               AND period_month = :periodMonth
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void generate(Long userId, LocalDate periodMonth) {
        entityManager.createNativeQuery("CALL sp_generate_monthly_insight(:userId, :periodMonth)")
                .setParameter("userId", userId)
                .setParameter("periodMonth", Date.valueOf(periodMonth))
                .executeUpdate();
    }

    @Transactional
    public int writeAiNarrative(Long userId, LocalDate periodMonth, String summaryText,
                                String adviceText, String modelName) {
        return entityManager.createNativeQuery(UPDATE_AI_NARRATIVE)
                .setParameter("userId", userId)
                .setParameter("periodMonth", Date.valueOf(periodMonth))
                .setParameter("summaryText", summaryText)
                .setParameter("adviceText", adviceText)
                .setParameter("generatedBy", InsightGeneratedBy.AI.name())
                .setParameter("modelName", modelName)
                .executeUpdate();
    }
}
