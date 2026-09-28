package com.campuscoin.anomaly.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The signed-in student's records that the anomaly check marked (UC-24).")
public record FlaggedTransactionListResponse(

        @Schema(description = "How many entries this response was allowed to return.", example = "20")
        int limit,

        @Schema(description = "The flagged records, most recent first. Empty when the student has "
                + "none, which is the ordinary case.")
        List<FlaggedTransactionResponse> entries) {
}
