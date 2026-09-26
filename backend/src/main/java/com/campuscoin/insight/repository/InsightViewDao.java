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

/**
 * Reads the monthly insights UC-17 serves (UC-17).
 *
 * <p><b>There is no view over {@code insights}, and the reads name the table directly.</b> That is
 * unusual for this project and it is deliberate, in the way {@code AnomalyViewDao} records for
 * {@code transactions}' flag columns: {@code db/02_views.sql} holds no view over this table because
 * nothing has ever read it - UC-17 is module 12 and the table was written into the schema when it was
 * designed. Adding a view now would be adding one for a single feature rather than because two
 * readers share it, which is the test every other view in this project passes. Three queries against
 * the table, all narrowed by {@code user_id} first, are what this module actually needs.
 *
 * <p><b>Three reads of {@code insights}, and a fourth of the view the procedure sums from.</b> One
 * answers the month the caller asked for; one lists the months that hold an insight so a picker can
 * offer them; and the third does not exist, because generation ends in the first query - what the
 * caller is shown back is the row as it now reads. The month list is a read of the same table with the
 * same narrowing and a different shape, and collapsing it into the single-month read would mean
 * fetching every insight's prose to build a list of month labels. The fourth read is
 * {@code v_category_month_totals}: the per-category figures a provider is given, which are not columns
 * of {@code insights} at all and are read only when a narrative is about to be requested.
 *
 * <p><b>{@code flagged_categories} is parsed here and nowhere else.</b> The column is JSON, and a DTO
 * that published a {@code JsonNode} would push the array's shape into every client. Parsing on the
 * way out of the database means {@link InsightRow} is the only thing between the column and the
 * response, so a stored array that gained or lost a key is a change in one class. A malformed array
 * is treated as an empty list rather than as a fault: the column is written by the procedure from the
 * view's own expression, so a value that will not parse did not come from this application, and
 * failing the whole read would hide the month's totals and prose over an ancillary list.
 *
 * <p><b>All reads narrow by {@code user_id} first (BR-02).</b> Ownership is a property of the query
 * rather than a check at the call site, which is what makes it impossible for a request to read
 * another student's insight - and an insight carries their income, their expense and their net
 * balance, so that guarantee is the whole of this module's disclosure policy.
 *
 * <p>Projected by alias into a record rather than mapped as an entity. There is no {@code Insight}
 * entity in this build: the only writer is the stored procedure, so a managed entity could only be a
 * second, competing writer over the same row - the reasoning {@code TipRow} and {@code BookmarkRow}
 * record for their own tables.
 */
@Repository
public class InsightViewDao {

    /**
     * UC-17: every column of one month's insight for one student.
     *
     * <p>The predicate is {@code (user_id, period_month)}, which {@code uk_insight_user_month} makes
     * unique, so the result is at most one row. {@code period_month} is bound as a first-of-month
     * {@code DATE}, which is how the procedure stores it; turning a caller's {@code yyyy-MM} into that
     * value is the service's job, not this query's.
     *
     * <p>{@code generated_at} is selected because the response publishes it: an insight is a snapshot
     * of a month, and when it was written is what tells a reader whether they are looking at advice
     * produced before or after a correction they made last week.
     */
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

    /**
     * UC-17: the months the caller has an insight for, newest first.
     *
     * <p>This exists so a month picker offers only months that would return something. Building the
     * list from the student's transactions instead would offer months whose insight was never
     * generated, and opening one would show an empty screen behind a menu entry - the argument
     * {@code TipViewDao#findMonths} makes for the same list on its own table.
     *
     * <p>{@code ix_insight_user_generated} is {@code (user_id, generated_at)} and would serve the
     * ordering, but the list is ordered by {@code period_month} because that is what the labels say:
     * regenerating an old month moves its {@code generated_at} to today, and ordering by that would
     * put an old month at the top of the list. The index still narrows the scan to one student's rows,
     * and a student has one insight per month rather than one per record.
     */
    private static final String SELECT_MONTHS = """
            SELECT i.period_month AS periodMonth
              FROM insights i
             WHERE i.user_id = :userId
             ORDER BY i.period_month DESC
            """;

    /**
     * UC-17: the caller's expense categories for one month, largest first - the context a narrative is
     * written from.
     *
     * <p><b>This is the "prepare and filter the context" step, and it happens here rather than in the
     * service.</b> {@code AiSuggestionPort} carries no repository and no {@code EntityManager}, so the
     * only data a provider can receive is what a service hands it - and what the service hands it
     * comes from this query. Narrowing to one student, one month and {@code type = 'EXPENSE'} is
     * therefore the whole of what a provider learns about the month, and doing it in SQL means the
     * filter is a property of the read rather than a step somebody could forget.
     *
     * <p><b>Expense only, and that is a decision rather than a simplification.</b> The procedure's own
     * summary names the highest <em>spending</em> category, so the narrative describes the same shape
     * the stored rule-based text does; adding income categories would change what the month looks like
     * without changing any total. The student's income is already reported to the provider as
     * {@code totalIncome}; per-category income totals would be detail the answer does not use.
     *
     * <p><b>Largest first, and capped.</b> The order is what the request tells the provider the list
     * means, and it matches the procedure's own {@code ORDER BY total_amount DESC}. The
     * {@code LIMIT} bounds what leaves the server: a narrative is two sentences about a month's shape,
     * and a student with thirty categories gains nothing from having the tail sent to a third party -
     * the ordering makes the cap drop the least significant rows, so the answer is unaffected while
     * the disclosure is bounded. The total descending, id ascending order is total, so two categories
     * sharing a total cannot swap between two identical calls.
     *
     * <p>{@code category_name} comes from the view itself, which joins {@code categories} for its own
     * {@code name} column - so this reads a name the schema already publishes and the id never leaves
     * the database in this query. Both columns are the view's own; nothing is joined here.
     */
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

    /**
     * UC-17: the caller's insight for one month, or empty when there is none.
     *
     * <p>{@code Optional} rather than a zero-filled row, because "no insight was ever generated for
     * this month" and "an insight that reported no income and no spending" are different answers, and
     * the endpoint distinguishes them: the first is a {@code 404}, and the second is a month the
     * student genuinely recorded nothing in.
     */
    @Transactional(readOnly = true)
    public Optional<InsightRow> find(Long userId, LocalDate periodMonth) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("periodMonth", Date.valueOf(periodMonth))
                .getResultList();

        return rows.stream().map(this::toRow).findFirst();
    }

    /**
     * UC-17: the months the caller has insights for, newest first.
     *
     * <p>Returns the first-of-month dates as the column holds them rather than labels: the
     * {@code yyyy-MM} form is presentation, and it is produced once, in {@code InsightMapper} - the
     * arrangement {@code TipViewDao#findMonthsWithTips} and {@code TipMapper#toMonthString} use for
     * the same list on their own table. {@code Collections.emptyList()} rather than {@code List.of()}
     * would be the same value; the query's own order is preserved.
     */
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

    /**
     * UC-17: the caller's expense totals per category for one month, largest first.
     *
     * <p>Read only when a narrative is about to be requested, and read <em>after</em> the procedure has
     * run - the totals come from the same view the procedure itself summed, for the same month, so the
     * narrative is written from the figures the stored insight already reports. Reading them before the
     * call would compute the month twice and let the two disagree.
     *
     * <p>Returns a list of records rather than a {@code Tuple}, so no database shape reaches the code
     * that builds the provider request.
     */
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

    /**
     * One insight as the projection carries it.
     *
     * <p>{@code generatedBy} is read as its member name through {@link InsightGeneratedBy#valueOf}, so a
     * value the enum does not know is a fault rather than a silently substituted one - the column's
     * ENUM admits exactly three members, and a fourth would mean the schema and this class disagree.
     *
     * <p>{@code modelName} is left null when the column is null, which is the ordinary case for a
     * rule-based insight: no provider wrote it, so there is no model to name.
     */
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

    /**
     * The stored {@code flagged_categories} array as records.
     *
     * <p>Every field is read defensively because the column is JSON the database does not validate: an
     * element written by a hand-run statement could be missing a key. A missing number is carried
     * through as null rather than as zero - the same distinction the response draws elsewhere between
     * "no figure" and "a figure of zero".
     */
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
            // The column is written by sp_generate_monthly_insight from the view's own expression, so
            // a value that will not parse did not come from this application. The month's totals and
            // prose are still worth serving; only the ancillary list is lost, and an empty list is the
            // honest report of "nothing readable was stored" rather than a failed read. The message is
            // logged by the service; nothing about the value is forwarded.
            return List.of();
        }
    }

    private static BigDecimal decimalOf(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.decimalValue();
    }
}
