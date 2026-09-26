package com.campuscoin.insight.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.campuscoin.insight.entity.InsightGeneratedBy;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One month's insight: what the month looked like, and where the words came from (UC-17).
 *
 * <p><b>The figures and the prose are served together because one is the evidence for the other.</b>
 * A student reading "you spent more than usual on Food" can only judge it against the totals and the
 * per-category figures beside it, and those are the same numbers the sentence was composed from -
 * {@code sp_generate_monthly_insight} writes the totals, the flagged categories and the rule-based
 * text in one statement. Serving the prose alone would ask the student to take it on trust; this
 * response is what lets them check it.
 *
 * <p><b>{@code generatedBy} is published, and it is the field BR-13 turns on.</b> BR-13 requires every
 * AI result to be presented as a suggestion the student reviews rather than as fact, and a student
 * cannot weigh a sentence without being told who wrote it. {@code RULE_BASED} means the database
 * composed it from their own figures by a fixed rule; {@code AI} means a provider wrote it from the
 * same figures. The response says which, and {@code model} names the model when there was one.
 *
 * <p><b>The disclaimer itself is not carried here, and deliberately so.</b> BR-13 fixes the wording a
 * screen must show beside an AI result, and that wording is Vietnamese: it is presentation copy, so
 * it belongs with the rest of the interface's copy in the Angular application rather than in a Java
 * string constant - this project's source is English, and a translated label embedded in a response
 * would be the one piece of the UI the client could not change. So the server publishes the
 * machine-readable signal a screen switches on ({@code generatedBy}) and states in this schema's
 * description what must be shown beside it; the client owns the words. A response that omitted
 * {@code generatedBy} would leave a client unable to tell an AI sentence from a rule-based one, which
 * is the failure this field exists to prevent.
 *
 * <p><b>{@code model} is omitted when no provider wrote the text</b>, which is the ordinary case in a
 * deployment with no AI provider configured. A null model would suggest a provider whose name was
 * lost; the absence says plainly that none was involved. It is deliberately not a fallback string
 * such as "built-in": {@code generatedBy} already says that, and one fact with two fields is a
 * second place for them to disagree.
 *
 * <p><b>{@code flaggedCategories} being empty is the ordinary case.</b> Most months have no category
 * running above its baseline; an empty array says the month was examined against the student's own
 * history and nothing stood out, which is a real answer rather than a missing one.
 *
 * <p>Absent, deliberately: {@code userId}, for the usual reason - the query already applied ownership
 * (BR-02), and an insight states a student's income, expense and net balance, so a response naming
 * its owner would publish the identifier beside the most sensitive figures in the system. Also
 * absent: {@code status} and {@code errorMessage}, which exist so a failed provider call can be
 * recorded and which this build never writes - publishing a field that is always null would describe
 * a state the feature does not have.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One month's insight for the signed-in student, with its figures and the source "
        + "of its wording (UC-17). The summary and advice are advisory: BR-13 requires a client to "
        + "present them as a suggestion the student reviews, to label them as a suggestion rather than "
        + "as financial advice, and to use `generatedBy` to say where the words came from.")
public record MonthlyInsightResponse(

        @Schema(description = "The month this insight describes, as `yyyy-MM`. Echoed back so a client "
                + "can confirm which month was answered.", example = "2026-09")
        String periodMonth,

        @Schema(description = "The month's total income, as the database computed it when the insight "
                + "was generated.", example = "260.00")
        BigDecimal totalIncome,

        @Schema(description = "The month's total spending.", example = "189.00")
        BigDecimal totalExpense,

        @Schema(description = "Income minus spending. Negative means the student spent more than they "
                + "received that month.", example = "71.00")
        BigDecimal netAmount,

        @Schema(description = "What the month looked like, in prose. Written by a provider when "
                + "`generatedBy` is `AI`, and composed from the figures above by a fixed rule when it "
                + "is `RULE_BASED`. Advisory: a suggestion, not financial advice (BR-13).",
                example = "2026-09: you received 260.00 and spent 189.00, net difference 71.00. "
                        + "Highest spending category: Food (95.00).")
        String summary,

        @Schema(description = "What the student might consider next month, framed as a suggestion. "
                + "Written by the same author as `summary`, and advisory for the same reason (BR-13).",
                example = "You are keeping a positive balance. Consider moving the surplus toward your "
                        + "savings goal at the start of the month.")
        String advice,

        @Schema(description = "Who wrote the prose: `AI` when a provider did, `RULE_BASED` when the "
                + "database composed it from the student's own figures by a fixed rule. The value a "
                + "client switches on to label the text honestly (BR-13).", example = "RULE_BASED")
        InsightGeneratedBy generatedBy,

        @Schema(description = "The provider's model identifier, stored when it wrote the text. Omitted "
                + "when no provider was involved.", example = "gemini-3.5-flash", nullable = true)
        String model,

        @Schema(description = "Expense categories that ran above the student's own usual level that "
                + "month, as the database flagged them (BR-15). Empty when nothing stood out, which is "
                + "the ordinary case.")
        List<FlaggedCategoryResponse> flaggedCategories,

        @Schema(description = "When this insight was generated. An insight is a snapshot of a month, so "
                + "this says whether it reflects a correction the student made afterwards.",
                example = "2026-09-26T20:14:05")
        LocalDateTime generatedAt) {

    /**
     * One expense category the database flagged as running above the student's own baseline that month
     * (UC-17, BR-15).
     *
     * <p><b>Five fields, because a flag is only useful with its evidence.</b> "You spent 340.00 on
     * Food" does not tell a student why that is worth noticing; "against your usual 110.00, up 209%"
     * does, and both figures were already stored in {@code insights.flagged_categories} when the
     * insight was written - they are not recomputed on read, so the comparison a student sees is the
     * one that made the database flag the category.
     *
     * <p><b>{@code baselineAvg} is an average, not a limit.</b> It is what the student spent in that
     * category over the previous months, divided by the window the {@code insight.spike_baseline_months}
     * setting names - it is a description of their own history, not a budget, and the response does not
     * present it as one.
     *
     * <p><b>{@code pctChange} is omitted when the baseline was zero.</b> The database computes a
     * percentage only where there is something to compare against, so a category the student had never
     * used before arrives with no percentage rather than an infinite one. An absent percentage and a
     * zero percent change mean different things - "nothing to compare against" against "exactly level" -
     * so it is passed through as stored instead of defaulted.
     *
     * <p>{@code categoryName} is carried beside the id because the flag is read on its own where the
     * category's name is what a student recognises, exactly as {@code FlaggedTransactionResponse}
     * carries both.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "An expense category that ran above the student's own usual level (UC-17, "
            + "BR-15).")
    public record FlaggedCategoryResponse(

            @Schema(description = "Identifier of the category (BR-05).", example = "4")
            Long categoryId,

            @Schema(description = "Name of that category.", example = "Food")
            String categoryName,

            @Schema(description = "What was spent in it that month.", example = "340.00")
            BigDecimal currentTotal,

            @Schema(description = "What the student usually spent in it over the preceding months - "
                    + "their own average, not a limit.", example = "110.00")
            BigDecimal baselineAvg,

            @Schema(description = "How far above the usual level that month was, as a percentage. "
                    + "Omitted when there was nothing to compare against.", example = "209.09",
                    nullable = true)
            BigDecimal pctChange) {
    }
}
