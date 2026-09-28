package com.campuscoin.recent.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The transactions the student most recently opened or changed (UC-26).")
public record RecentActivityListResponse(

        @Schema(description = "How many entries the server was asked for and applied. Echoed so a "
                + "client can tell a list that ends because the student has seen little apart from "
                + "one that was capped.", example = "10")
        int limit,

        @Schema(description = "The entries, most recent first. Empty when the student has not yet "
                + "opened or changed anything.")
        List<RecentActivityResponse> entries) {
}
