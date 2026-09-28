package com.campuscoin.insight.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.common.jdbc.JdbcValues;
import com.campuscoin.insight.entity.InsightGeneratedBy;
import com.campuscoin.insight.entity.InsightRow;
import com.campuscoin.insight.entity.MonthlyCategoryTotal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class InsightViewDao {

    private static final String SELECT_ONE = """
            SELECT i.period_month       AS periodMonth,
                   i.summary_text       AS summaryText,
                   i.advice_text        AS adviceText,
                   i.flagged_categories AS flaggedCategories,
                   i.total_income       AS totalIncome,
                   i.total_expense      AS totalExpense,
                   i.net_amount         AS netAmount,
                   i.generated_by       AS generatedBy,
                   i.model_name         AS modelName,
                   i.generated_at       AS generatedAt
              FROM insights i
             WHERE i.user_id = :userId
               AND i.period_month = :periodMonth
            """;

    private static final String SELECT_MONTHS = """
            SELECT i.period_month AS periodMonth
              FROM insights i
             WHERE i.user_id = :userId
             ORDER BY i.period_month DESC
            """;

    private static final String SELECT_CATEGORY_TOTALS = """
            SELECT v.category_name AS categoryName,
                   v.total_amount  AS totalAmount
              FROM v_category_month_totals v
             WHERE v.user_id = :userId
               AND v.period_month = :periodMonth
               AND v.type = 'EXPENSE'
             ORDER BY v.total_amount DESC, v.category_id ASC
             LIMIT 10
            """;

    private final ObjectMapper objectMapper;

    public InsightViewDao(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<InsightRow> find(Long userId, LocalDate periodMonth) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", Date.valueOf(periodMonth))
                .getResultList();

        return rows.stream().map(this::toRow).findFirst();
    }

    @Transactional(readOnly = true)
    public List<LocalDate> findMonths(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_MONTHS, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        List<LocalDate> months = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            months.add(row.get("periodMonth", Date.class).toLocalDate());
        }
        return List.copyOf(months);
    }

    @Transactional(readOnly = true)
    public List<MonthlyCategoryTotal> findExpenseTotalsByCategory(Long userId, LocalDate periodMonth) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_CATEGORY_TOTALS, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", Date.valueOf(periodMonth))
                .getResultList();

        List<MonthlyCategoryTotal> totals = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            totals.add(new MonthlyCategoryTotal(
                    row.get("categoryName", String.class),
                    row.get("totalAmount", BigDecimal.class)));
        }
        return List.copyOf(totals);
    }

    private InsightRow toRow(Tuple row) {
        return new InsightRow(
                row.get("periodMonth", Date.class).toLocalDate(),
                row.get("summaryText", String.class),
                row.get("adviceText", String.class),
                parseFlaggedCategories(row.get("flaggedCategories", String.class)),
                row.get("totalIncome", BigDecimal.class),
                row.get("totalExpense", BigDecimal.class),
                row.get("netAmount", BigDecimal.class),
                InsightGeneratedBy.valueOf(row.get("generatedBy", String.class)),
                row.get("modelName", String.class),
                JdbcValues.toLocalDateTime(row.get("generatedAt", Timestamp.class)));
    }

    private List<InsightRow.FlaggedCategory> parseFlaggedCategories(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                return List.of();
            }

            List<InsightRow.FlaggedCategory> flagged = new ArrayList<>(array.size());
            for (JsonNode element : array) {
                flagged.add(new InsightRow.FlaggedCategory(
                        element.path("categoryId").isNull() || element.path("categoryId").isMissingNode()
                                ? null : element.path("categoryId").asLong(),
                        element.path("categoryName").isMissingNode()
                                ? null : element.path("categoryName").asText(),
                        decimalOf(element.path("currentTotal")),
                        decimalOf(element.path("baselineAvg")),
                        decimalOf(element.path("pctChange"))));
            }
            return List.copyOf(flagged);
        } catch (Exception ex) {

            return List.of();
        }
    }

    private static BigDecimal decimalOf(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.decimalValue();
    }
}
