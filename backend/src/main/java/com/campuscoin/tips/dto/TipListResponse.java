package com.campuscoin.tips.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One month's saving tips, already ranked (UC-18).")
public record TipListResponse(

        @Schema(description = "The month these tips belong to, as `yyyy-MM`.", example = "2026-09")
        String periodMonth,

        @Schema(description = "The tips to show, pinned first and then by potential saving (BR-14). "
                + "Empty when the month has no visible tips.")
        List<TipResponse> tips) {
}
