package com.campuscoin.insight.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One stored monthly insight, as UC-17 reads it (UC-17).
 *
 * <p><b>The row is the procedure's, and this projection adds nothing to it.</b>
 * {@code sp_generate_monthly_insight} computes the month's totals, decides which expense categories
 * are running above their baseline, and writes both prose sentences; this record carries exactly
 * those nine fields so a read can hand back what is stored rather than a second derivation of it.
 * Nothing here is recomputed - {@code total_income}, {@code total_expense} and {@code net_amount} are
 * the columns, not a sum over transactions, which is what keeps the figures a stored insight reports
 * identical to the figures it was written from.
 *
 * <p><b>{@code flaggedCategories} is the one field that is not a column's own value.</b>
 * {@code insights.flagged_categories} is a JSON array, and it is parsed on the way out of the
 * database into {@link FlaggedCategory} records so no JSON reaches a DTO. The array is stored rather
 * than recomputed on read - the schema's own comment says why: "flagged_categories stores the list of
 * unusual categories so it does not have to be recomputed" - so an insight read in December still
 * names the categories that were unusual in the month it describes, even after the student's later
 * spending has moved their monthly averages.
 *
 * <p><b>{@code summaryText}, {@code adviceText} and {@code generatedBy} are carried together on
 * purpose.</b> They are the three fields that answer "who wrote this, and what did they write", and
 * a projection that carried the prose without its author would let a caller publish a sentence
 * without being able to say where it came from - which is what BR-13 requires a response to say.
 *
 * <p><b>{@code userId} is deliberately not carried.</b> Every query narrows on it, so it is the same
 * value on every row of an answer and a client has no use for it - the reasoning {@code TipRow},
 * {@code BookmarkRow}, {@code RecentActivityRow} and {@code FlaggedTransactionRow} all record.
 *
 * <p>{@code status} and {@code errorMessage} are deliberately not carried. They exist so a failed AI
 * call can be recorded, and this build writes neither; publishing a field that is always null would
 * describe a state the feature does not have. See {@code InsightService} for what happens when the
 * provider is unreachable - the rule-based text stands and nothing is marked failed.
 */
public record InsightRow(
        LocalDate periodMonth,
        String summaryText,
        String adviceText,
        List<FlaggedCategory> flaggedCategories,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal netAmount,
        InsightGeneratedBy generatedBy,
        String modelName,
        LocalDateTime generatedAt) {

    /**
     * One expense category the procedure flagged as running above its own baseline that month
     * (UC-17, BR-15).
     *
     * <p>The five fields are exactly what {@code sp_generate_monthly_insight} writes into
     * {@code flagged_categories} - {@code categoryId}, {@code categoryName}, {@code currentTotal},
     * {@code baselineAvg} and {@code pctChange} - read from {@code v_category_spend_trend}, whose own
     * definition of a spike this module must not restate. A category is in this list because the view
     * said {@code is_spike = 1}, not because Java compared the two figures again.
     *
     * <p><b>{@code baselineAvg} and {@code pctChange} are carried, not just the total.</b> UC-17's
     * postcondition is that a student can see why a month was unusual, and "you spent 340.00 on Food"
     * does not say that; "against your usual 110.00, up 209%" does. Both figures are already in the
     * stored array, and deriving them on the client from the total alone is not possible.
     *
     * <p>{@code pctChange} is nullable because the view's own expression is: it computes a percentage
     * only where the baseline is greater than zero, so a category with no prior history arrives with
     * the field absent. An absent percentage and a zero percent change mean different things - "there
     * was nothing to compare against" against "exactly level" - so it is passed through as it stands
     * rather than defaulted to zero.
     */
    public record FlaggedCategory(
            Long categoryId,
            String categoryName,
            BigDecimal currentTotal,
            BigDecimal baselineAvg,
            BigDecimal pctChange) {
    }
}
