package com.campuscoin.insight.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The months a student has an insight for (UC-17).
 *
 * <p><b>An object with one array rather than a bare JSON array.</b> A top-level array cannot gain a
 * field without breaking every client that reads it as a list, and this response is the natural place
 * for a later "which month is current" or "how many" if a screen needs one. It is the shape
 * {@code TipMonthsResponse} has for the same list on its own table, so a client parses one convention
 * rather than two.
 *
 * <p><b>The list can be empty, and an empty list is the honest answer.</b> A student whose insight has
 * never been generated has no months to show, so this returns {@code {"months": []}} rather than
 * inventing a current month that would sit behind an empty screen. The current month is not special
 * here: it appears only if an insight for it was actually produced.
 *
 * <p><b>An insight covers its month whether or not the student recorded anything in it.</b> The
 * procedure writes a row for the month it was asked for and reports zero totals when there were no
 * records, so a listed month is a month an insight was generated for rather than a month with
 * spending in it. That is why this list is read from {@code insights} and not derived from the
 * student's transactions: a month they generated an insight for is one this API would answer with
 * content.
 *
 * <p><b>Newest first, by the month and not by when it was generated.</b> Regenerating an old month
 * writes today's timestamp onto that older month, so ordering by {@code generated_at} would move it to
 * the top of a list whose labels read oldest-to-newest - the reasoning
 * {@code InsightViewDao#SELECT_MONTHS} records.
 */
@Schema(description = "The months the signed-in student has an insight for, newest first (UC-17).")
public record InsightMonthsResponse(

        @Schema(description = "Months in `yyyy-MM` form, newest first. Empty when no insight has been "
                + "generated for the caller.", example = "[\"2026-09\", \"2026-08\"]")
        List<String> months) {
}
