package com.campuscoin.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
