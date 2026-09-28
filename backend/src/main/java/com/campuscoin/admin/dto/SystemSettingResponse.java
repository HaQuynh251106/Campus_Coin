package com.campuscoin.admin.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A business threshold or configuration value (UC-23, VĐ-05).")
public record SystemSettingResponse(

        @Schema(description = "The setting's key, as used in the path of the update endpoint.",
                example = "budget.near_threshold_pct")
        String key,

        @Schema(description = "The stored value, as text. `valueType` says how to read it.",
                example = "80")
        String value,

        @Schema(description = "How to interpret `value`: `STRING`, `INT`, `DECIMAL`, `BOOLEAN` or "
                + "`JSON`.", example = "DECIMAL")
        String valueType,

        @Schema(description = "What the setting controls. Omitted when the row has no description.",
                example = "Percentage of a spending limit at which a near-budget tip is raised.",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String description,

        @Schema(description = "True when this key may be changed through "
                + "`PATCH /api/v1/admin/settings/{key}`. False means the row is read-only through "
                + "the API - either the deployment sets it or another module reads it.",
                example = "true")
        Boolean adjustable) {
}
