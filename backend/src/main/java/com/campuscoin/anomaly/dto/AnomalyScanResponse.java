package com.campuscoin.anomaly.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What one anomaly scan examined and what it changed (UC-24).
 *
 * <p><b>The counts are the point of this response, and the entries are its result.</b> A scan is the
 * only endpoint in this module that writes, and it writes only where its conclusion differs from what
 * is stored - so "how many records did you look at, and how many did you change" is what tells a
 * client whether pressing the button did anything. Without the counts a repeated scan, which
 * legitimately changes nothing at all, would be indistinguishable from a scan that failed to run.
 *
 * <p><b>{@code flagged} and {@code cleared} are separate rather than one "changed" figure.</b> They
 * mean opposite things to the student - "the system found something new" and "the system withdrew a
 * finding you have since corrected" - and a client that wanted to say either would otherwise have to
 * diff the list against the previous one. {@code unchanged} completes the accounting:
 * {@code examined = flagged + cleared + unchanged}, and the sum is asserted in the module's tests so a
 * later change that made the three disagree with the first would fail rather than quietly mislead.
 *
 * <p><b>{@code entries} is the flagged list as it stands after the scan</b>, read back through the
 * same query the read endpoint uses rather than assembled from the verdicts. So a client never needs a
 * second call to see the new state, and the list it is handed cannot disagree with what
 * {@code GET /api/v1/transactions/anomalies} would return a moment later. It is the shape
 * {@code TipService}'s generate endpoint uses for the same reason.
 *
 * <p>{@code limit} is not carried here, unlike on the read endpoint: a scan examines every live record
 * and its list is not the caller's to bound. What bounds it is {@code examined}, which is reported.
 */
@Schema(description = "The result of one anomaly scan over the signed-in student's own records "
        + "(UC-24).")
public record AnomalyScanResponse(

        @Schema(description = "How many of the student's live records were examined. The duplicate and "
                + "unusual-amount rules are both relative to the student's own history, so the scan "
                + "reads all of it rather than a window.", example = "31")
        int examined,

        @Schema(description = "How many records were marked by this scan that were not marked before.",
                example = "2")
        int flagged,

        @Schema(description = "How many records this scan un-marked, because the student corrected "
                + "them or the record that made them a duplicate was put in the trash.", example = "1")
        int cleared,

        @Schema(description = "How many records were examined and left exactly as they were. A "
                + "repeated scan over unchanged data reports every record here and writes nothing.",
                example = "28")
        int unchanged,

        @Schema(description = "The flagged records as they stand after the scan, most recent first - "
                + "the same list the read endpoint returns.")
        List<FlaggedTransactionResponse> entries) {
}
