package com.campuscoin.tips.mapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.tips.dto.TipListResponse;
import com.campuscoin.tips.dto.TipMonthsResponse;
import com.campuscoin.tips.dto.TipResponse;
import com.campuscoin.tips.entity.TipRow;
import com.campuscoin.tips.entity.UserTip;

/**
 * Turns UC-18's tip rows into the responses (UC-18).
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the other mappers
 * give: it is the single place that decides which columns may leave the server. Every source here
 * carries {@code user_id} and {@code rank_score}, and centralising the projection means a column can
 * be added to a view or an entity without silently appearing in a response.
 *
 * <p><b>Nothing is recalculated.</b> The order of the list arrives from the view already decided and
 * is passed through unaltered; re-sorting by {@code potentialSaving} would be a second definition of
 * BR-14's ranking and would let a pinned tip drift. The amounts inside the title and body were
 * rendered by the database from a template and are passed through as prose.
 *
 * <p>The one conversion owned here is the month: the database stores it as the {@code DATE} of the
 * first day and a client names it as {@code yyyy-MM}. It is done once, in {@link #toMonthString}, so
 * no caller has to remember the convention, and it is the same conversion {@code ReportMapper},
 * {@code BudgetMapper} and {@code DashboardMapper} make - a tip month, a report month and a budget
 * month must be the same string for the same month.
 */
@Component
public class TipMapper {

    /**
     * UC-18: one month's tips, with the month named once.
     *
     * <p>The rows are taken in the order the DAO returned them - pinned first, then by BR-14's score -
     * and never re-sorted.
     */
    public TipListResponse toListResponse(LocalDate periodMonth, List<TipRow> rows) {
        return new TipListResponse(
                toMonthString(periodMonth),
                rows.stream().map(this::toTipResponse).toList());
    }

    /** UC-18: a tip just loaded for a state change, mapped from the entity rather than the view. */
    public TipResponse toTipResponse(UserTip tip) {
        return new TipResponse(
                tip.getId(),
                tip.getCategoryId(),
                tip.getTitle(),
                tip.getBody(),
                tip.getPotentialSaving(),
                tip.getState());
    }

    /** UC-18: the months that hold a visible tip, newest first. */
    public TipMonthsResponse toMonthsResponse(List<LocalDate> months) {
        return new TipMonthsResponse(months.stream().map(this::toMonthString).toList());
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

    /** One view row as a response, used by {@link #toListResponse}. */
    private TipResponse toTipResponse(TipRow row) {
        return new TipResponse(
                row.tipId(),
                row.categoryId(),
                row.title(),
                row.body(),
                row.potentialSaving(),
                row.state());
    }
}
