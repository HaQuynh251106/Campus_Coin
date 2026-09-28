package com.campuscoin.tips.dto;

import com.campuscoin.tips.entity.TipState;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "The new state for a tip (UC-18).")
public record UpdateTipStateRequest(

        @Schema(description = "`PINNED` shows the tip first and keeps its place; `DISMISSED` removes "
                + "it from the list for good; `NEW` clears either, which is how a tip is unpinned.",
                example = "PINNED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Choose a state: PINNED, DISMISSED or NEW.")
        TipState state) {
}
