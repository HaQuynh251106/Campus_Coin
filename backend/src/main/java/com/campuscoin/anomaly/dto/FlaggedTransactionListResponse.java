package com.campuscoin.anomaly.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The caller's flagged records, with the limit that was applied (UC-24).
 *
 * <p><b>Why the limit is on the wrapper.</b> The list is bounded by a parameter the caller supplies,
 * so an answer that did not report the bound it used would leave a client unable to tell a short list
 * from a capped one. Stating it once here means the field cannot drift from the query that applied it
 * - the arrangement {@code RecentActivityListResponse} uses for the same reason.
 *
 * <p><b>{@code entries} is present and empty when nothing is flagged</b>, which is the ordinary case
 * for most students. An empty array is the honest answer: the student's own records were examined and
 * none look wrong. The endpoint does not answer {@code 404}, because there is no missing resource -
 * and "you have no anomalies" is exactly the outcome UC-24 wants to be able to report.
 *
 * <p>There is deliberately no {@code total}. Counting every flagged record across a student's whole
 * history would mean a second query whose answer contradicts the list the moment the caller trims it
 * with a smaller limit; the count of what is shown is {@code entries.length}, and a client that wants
 * more asks for more.
 */
@Schema(description = "The signed-in student's records that the anomaly check marked (UC-24).")
public record FlaggedTransactionListResponse(

        @Schema(description = "How many entries this response was allowed to return.", example = "20")
        int limit,

        @Schema(description = "The flagged records, most recent first. Empty when the student has "
                + "none, which is the ordinary case.")
        List<FlaggedTransactionResponse> entries) {
}
