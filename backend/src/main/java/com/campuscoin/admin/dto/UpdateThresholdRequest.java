package com.campuscoin.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/v1/admin/settings/{key}} (UC-23, VĐ-05): change one threshold.
 *
 * <p><b>The key is in the path and the value is in the body, so the value arrives as the string the
 * column stores.</b> {@code setting_value} is {@code VARCHAR(255)} and the six adjustable keys are
 * all numbers, but typing this field as a number would make the API unable to carry any future key
 * that is not one - {@code app.currency} is {@code USD} - and would force the shape check to happen
 * in two places. The service validates the value against the key's shape, so a caller that sends
 * {@code "ninety"} to a percentage key gets a field error naming the field rather than a database
 * refusal.
 *
 * <p><b>The value is validated before the procedure is called, and that ordering is deliberate.</b>
 * {@code sp_admin_set_threshold} refuses with SQLSTATE 45000 for every one of its three conditions -
 * the key is not on its allow-list, the value does not fit the key's shape, the key is not in
 * {@code system_settings} - so the exception alone does not say which input was wrong. Checking the
 * key and the value here means each refusal reaches the client already labelled, and the service's
 * own answer names the `value` field. See {@code docs/modules/MODULE_11_ADMINISTRATION.md}.
 *
 * <p>Required, because a threshold with no value has none of the meanings an absent value could be
 * given, and {@code null} would reach the procedure as a value the cast cannot read.
 */
@Schema(description = "The new value for a threshold (UC-23, VĐ-05).")
public record UpdateThresholdRequest(

        @Schema(description = "The new value, as text. Read according to the key: the percentage "
                + "and count keys take positive numbers, and `insight.spike_baseline_months` takes "
                + "a whole number from 1 to 12.", example = "85",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "A value is required.")
        @Size(max = 255, message = "Value must be at most 255 characters.")
        String value) {
}
