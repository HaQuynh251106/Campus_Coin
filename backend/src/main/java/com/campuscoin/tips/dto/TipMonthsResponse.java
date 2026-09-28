package com.campuscoin.tips.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The months that hold at least one visible tip, newest first (UC-18).")
public record TipMonthsResponse(

        @Schema(description = "Months in `yyyy-MM` form, newest first. Empty when no tips have been "
                + "generated for the caller.", example = "[\"2026-09\", \"2026-08\"]")
        List<String> months) {
}
