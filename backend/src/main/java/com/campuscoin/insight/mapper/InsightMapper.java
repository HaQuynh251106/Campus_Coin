package com.campuscoin.insight.mapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.insight.dto.InsightMonthsResponse;
import com.campuscoin.insight.dto.MonthlyInsightResponse;
import com.campuscoin.insight.entity.InsightRow;

/**
 * Turns UC-17's stored insights into the responses (UC-17).
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the other mappers
 * give: it is the single place that decides which columns may leave the server. The insight row
 * carries a student's income, expense and net balance, so the projection is not a formality - it is
 * where the guarantee that no owner identifier leaves with those figures is kept, and keeping it in
 * one class means a new column cannot reach a client by being added to a table.
 *
 * <p><b>Nothing here is recalculated, and the flagged figures are passed through as stored.</b> The
 * totals, the category list inside it, and both prose sentences all come from
 * {@code sp_generate_monthly_insight}; re-deriving a percentage or re-ranking the flagged list would
 * be a second definition of BR-15's spike test beside the view's own, and the two would eventually
 * disagree about the month a student is looking at.
 *
 * <p><b>The one conversion owned here is the month</b>, exactly as {@code TipMapper#toMonthString}
 * owns it for its table: the database stores {@code period_month} as the {@code DATE} of the first day
 * and a client names the month as {@code yyyy-MM}. It is done once, in {@link #toMonthString}, so no
 * caller has to remember the convention, and a report month, a budget month and an insight month are
 * the same string for the same month.
 *
 * <p><b>No disclaimer is attached here.</b> BR-13 requires the words "a suggestion, not financial
 * advice" beside an AI result, and {@link MonthlyInsightResponse} records why the response carries the
 * signal ({@code generatedBy}) rather than the sentence: the wording is interface copy, and this
 * project's source is English. The mapper's job is to publish the flag honestly, which is the part a
 * client cannot work out for itself.
 */
@Component
public class InsightMapper {

    /**
     * UC-17: one stored insight as the response.
     *
     * <p>The flagged categories are mapped one for one, in the order the stored array has them - which
     * is the view's own order, largest total first - so the list a student sees is the list the
     * procedure wrote rather than a re-sorted one.
     */
    public MonthlyInsightResponse toInsightResponse(InsightRow row) {
        return new MonthlyInsightResponse(
                toMonthString(row.periodMonth()),
                row.totalIncome(),
                row.totalExpense(),
                row.netAmount(),
                row.summaryText(),
                row.adviceText(),
                row.generatedBy(),
                row.modelName(),
                row.flaggedCategories().stream().map(InsightMapper::toFlaggedCategory).toList(),
                row.generatedAt());
    }

    /** UC-17: the months that hold an insight, newest first, as the `yyyy-MM` a client names them. */
    public InsightMonthsResponse toMonthsResponse(List<LocalDate> months) {
        return new InsightMonthsResponse(months.stream().map(this::toMonthString).toList());
    }

    /**
     * A first-of-month {@code DATE} as the {@code yyyy-MM} a client uses.
     *
     * <p>{@link YearMonth#from} rather than slicing the string: every {@code period_month} the
     * database produces is the first of its month, so the year and the month are the whole of what it
     * carries, and taking them through the date type cannot produce a value the calendar disagrees
     * with.
     *
     * @param periodMonth a date the database guarantees is the first of its month, or null
     * @return the month as {@code yyyy-MM}, or null when the value was null
     */
    public String toMonthString(LocalDate periodMonth) {
        return periodMonth == null ? null : YearMonth.from(periodMonth).toString();
    }

    /**
     * One flagged category, field for field.
     *
     * <p>{@code pctChange} is carried through as it stands, including when it is null: the view
     * computes a percentage only where the baseline was greater than zero, and "there was nothing to
     * compare against" is not the same statement as "no change", so the absence is preserved rather
     * than defaulted to zero.
     */
    private static MonthlyInsightResponse.FlaggedCategoryResponse toFlaggedCategory(
            InsightRow.FlaggedCategory flagged) {
        return new MonthlyInsightResponse.FlaggedCategoryResponse(
                flagged.categoryId(),
                flagged.categoryName(),
                flagged.currentTotal(),
                flagged.baselineAvg(),
                flagged.pctChange());
    }
}
