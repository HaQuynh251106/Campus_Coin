package com.campuscoin.imports.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The student's CSV imports, most recent first (UC-11).")
public record ImportBatchListResponse(

        @Schema(description = "How many entries the server was asked for and applied. Echoed so a "
                + "client can tell a list that ends because the student has imported little apart "
                + "from one that was capped.", example = "20")
        int limit,

        @Schema(description = "The imports, most recent first. Empty when the student has never "
                + "imported a file.")
        List<ImportSummaryResponse> entries) {
}
