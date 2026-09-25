package com.campuscoin.tips.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The months a student has tips to show for (UC-18).
 *
 * <p><b>An object with one array rather than a bare JSON array.</b> A top-level array cannot gain a
 * field without breaking every client that reads it as a list, and this response is the natural place
 * for a later "which month is current" or "how many" if a screen needs one. It is also how
 * {@code ReportResponse} and the other list-bearing responses are shaped, so a client parses one
 * convention rather than two.
 *
 * <p><b>The list can be empty, and an empty list is the honest answer.</b> A student whose tips have
 * never been generated has no months with tips, so this returns {@code {"months": []}} rather than
 * inventing a current month that would sit behind an empty screen. The current month is not special
 * here: it is present only if the student actually has tips for it, which is exactly what a month
 * picker needs to know.
 *
 * <p><b>Only months with at least one visible tip appear.</b> A month whose tips have all been
 * dismissed drops out of the list, because it would return nothing if asked for - the list is the set
 * of months this API's tips endpoint would answer with content, not every month the student has ever
 * had advice in.
 */
@Schema(description = "The months that hold at least one visible tip, newest first (UC-18).")
public record TipMonthsResponse(

        @Schema(description = "Months in `yyyy-MM` form, newest first. Empty when no tips have been "
                + "generated for the caller.", example = "[\"2026-09\", \"2026-08\"]")
        List<String> months) {
}
