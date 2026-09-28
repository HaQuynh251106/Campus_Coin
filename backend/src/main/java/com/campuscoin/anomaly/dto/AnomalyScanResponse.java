package com.campuscoin.anomaly.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

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
