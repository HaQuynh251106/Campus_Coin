package com.campuscoin.admin.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One business threshold as the administration screen shows it (UC-23, VĐ-05).
 *
 * <p><b>{@code adjustable} is the field that makes this response usable rather than merely
 * informative.</b> VĐ-05 stores every business threshold in {@code system_settings}, but
 * {@code sp_admin_set_threshold} permits only six keys - the ones whose values the running system
 * actually reads. The other ten rows exist for different reasons: {@code app.currency} is set for
 * the deployment, {@code auth.session_ttl_minutes} and {@code auth.max_login_attempts} are read by
 * Java rather than by this procedure, and {@code ai.enabled} belongs to a capability this build does
 * not have. Listing all sixteen with no indication of which can be changed would invite a client to
 * offer an edit that the database refuses, so the flag says so up front. It is computed by
 * {@code AdminThresholds}, which is the same allow-list the PATCH endpoint enforces, so the two can
 * never disagree.
 *
 * <p>{@code value} is the stored string, not a parsed number: {@code setting_value} is
 * {@code VARCHAR(255)} and its interpretation comes from {@code valueType}. Returning it as sent
 * keeps this response honest about what the column holds and avoids inventing a number for a value
 * that is not one - {@code app.currency} is {@code USD}, and {@code app.timezone} is a name.
 *
 * <p>Deliberately absent: {@code updatedBy} and {@code updatedAt}. The audit trail records who
 * changed a threshold and when, which is what UC-22 B5 asks for; a second per-row copy would be a
 * weaker answer to the same question.
 */
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
