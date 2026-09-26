package com.campuscoin.insight.repository;

import java.sql.Date;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.insight.entity.InsightGeneratedBy;

/**
 * Writes UC-17's insights: the procedure that computes them, and the one statement the AI path needs
 * on top of it (UC-17).
 *
 * <p><b>Why the base figures come from a procedure and not from a statement here.</b> The
 * application's database account holds direct grants on {@code insights}, so an {@code INSERT} from
 * this class would work - and it would be the wrong write, because
 * {@code sp_generate_monthly_insight} owns decisions this module must not restate: which month it is
 * when none was named, how income and expense are summed from the category's own type (BR-05) over
 * records not in the trash (BR-09), which categories count as spikes by joining
 * {@code v_category_spend_trend} to the two {@code insight.*} settings (BR-15), and the exact
 * rule-based prose it writes when no provider is configured. Reimplementing any of those in Java
 * would be a second answer to a question the schema already answers once, and the two would
 * eventually disagree about a month boundary or a threshold.
 *
 * <p><b>The upsert's AI-preservation rule is the procedure's, and this class relies on it rather than
 * repeating it.</b> Its {@code ON DUPLICATE KEY UPDATE} writes
 * {@code summary_text = IF(insights.generated_by = 'AI', insights.summary_text, v_summary)} - so a
 * later run rewrites its own rule-based text and leaves a provider's text alone. That means the
 * sequence in this class is "run the procedure, then overlay the provider's answer", and a generation
 * that reaches no provider simply stops after the procedure: the row keeps whatever it had, and a
 * month already carrying an AI narrative keeps it. Neither half of that rule is re-decided here.
 *
 * <p><b>Not {@code @Transactional(readOnly = true)} on the call.</b> MySQL refuses to execute a
 * {@code CALL} on a read-only connection outright, so the plain annotation is required for the same
 * reason {@code TipGenerationDao}, {@code RecentActivityProcedureDao} and
 * {@code AnomalyFlagProcedureDao} use it - it is a property of the driver, not a claim that the call
 * is read-only.
 */
@Repository
public class InsightWriteDao {

    /**
     * UC-17: the provider's narrative, written over the rule-based text the procedure just stored.
     *
     * <p><b>Only the three columns a provider's answer changes are written.</b> The figures, the
     * flagged categories and the period are the procedure's and are left exactly as it wrote them -
     * the narrative is generated from those same figures ({@code MonthlyNarrativeRequest} is built
     * from the row the call just produced), so rewriting them here from the provider's prose would put
     * the numbers in two places and let them drift.
     *
     * <p><b>{@code generated_by = 'AI'} is what makes the text survive, and it is set in the same
     * statement as the text.</b> The procedure preserves {@code summary_text} only when the stored
     * {@code generated_by} is {@code AI}, so a statement that wrote the prose without the marker would
     * leave text a later run would silently overwrite - and a statement that wrote the marker without
     * the prose would protect a sentence nobody wrote. They move together or not at all.
     *
     * <p>{@code model_name} is stored from the provider's own report, and is null when it reported
     * none: the column is what lets an operator tell which model produced a stored insight, and
     * inventing a name for an answer that came without one would defeat that.
     *
     * <p>The predicate repeats the read's narrowing rather than trusting it: {@code user_id} is checked
     * here too, so the statement cannot reach another student's row even if a caller passed the wrong
     * id. The caller compares the affected-row count with the one it expected, because a write matching
     * nothing would mean the row moved underneath the request rather than that the write was
     * unnecessary - the unnecessary case never reaches here.
     *
     * @return the number of rows changed; {@code 1} is the only acceptable answer
     */
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

    /**
     * UC-17: computes and stores the month's insight, without any provider's involvement.
     *
     * <p>Returns nothing, because the procedure returns nothing. What a caller is shown back is the
     * row as it now reads, which {@code InsightViewDao} supplies - so the response is rendered from
     * the same query the read endpoint uses rather than from a second, hand-built object that could
     * disagree with it. It is the shape {@code TipGenerationDao#generateTips} has for the same reason.
     *
     * <p>Parameters are bound by name and never interpolated, and the month is passed as a
     * first-of-month {@code DATE} - the form the procedure's own {@code DATE_FORMAT} default produces
     * and the form {@code uk_insight_user_month} keys on, so a named month and a defaulted one land on
     * the same row.
     */
    @Transactional
    public void generate(Long userId, LocalDate periodMonth) {
        entityManager.createNativeQuery("CALL sp_generate_monthly_insight(:userId, :periodMonth)")
                .setParameter("userId", userId)
                .setParameter("periodMonth", Date.valueOf(periodMonth))
                .executeUpdate();
    }

    /**
     * UC-17: stores a provider's narrative over the rule-based text, and marks the row as the
     * provider's.
     *
     * <p>The two halves of the audit trail move together: the prose, and the fact that a provider
     * wrote it ({@link com.campuscoin.insight.entity.InsightGeneratedBy#AI}) with the model's name
     * beside it. {@code InsightService} calls this only when a provider actually returned a narrative,
     * so a deployment with none never reaches this statement at all - which is why the rule-based path
     * leaves no trace here rather than a trace reading "no model".
     *
     * @param userId      the caller, whose own row this is
     * @param periodMonth the month the narrative describes, as its first day
     * @param summaryText what the provider wrote about the month
     * @param adviceText  what it suggested for next month, framed as a suggestion (BR-13)
     * @param modelName   the provider's model identifier, or null when it reported none
     * @return the number of rows changed; {@code 1} is the only acceptable answer
     */
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
