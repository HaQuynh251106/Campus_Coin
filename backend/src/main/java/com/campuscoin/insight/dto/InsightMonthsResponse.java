package com.campuscoin.insight.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The months the signed-in student has an insight for, newest first (UC-17).")
public record InsightMonthsResponse(

        @Schema(description = "Months in `yyyy-MM` form, newest first. Empty when no insight has been "
                + "generated for the caller.", example = "[\"2026-09\", \"2026-08\"]")
        List<String> months) {
}
