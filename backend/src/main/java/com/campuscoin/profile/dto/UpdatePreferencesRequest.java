package com.campuscoin.profile.dto;

import com.campuscoin.auth.entity.FontScale;
import com.campuscoin.auth.entity.ThemePreference;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The display preferences to change. Omit a field to leave it as it is (UC-27).")
public record UpdatePreferencesRequest(

        @Schema(description = "Appearance preference. SYSTEM follows the operating system.",
                example = "SYSTEM", nullable = true)
        ThemePreference themePreference,

        @Schema(description = "Text size preference.", example = "MEDIUM", nullable = true)
        FontScale fontScale) {
}
