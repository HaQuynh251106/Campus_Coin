package com.campuscoin.profile.dto;

import com.campuscoin.auth.entity.FontScale;
import com.campuscoin.auth.entity.ThemePreference;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code PATCH /api/v1/profile/me/preferences} (UC-27).
 *
 * <p>Both fields are optional, so changing the theme does not require resending the text size.
 * An absent field is left alone.
 *
 * <p>The values are the schema's ENUM members, named exactly as the column stores them
 * ({@code LIGHT}, {@code DARK}, {@code SYSTEM} and {@code SMALL}, {@code MEDIUM}, {@code LARGE},
 * {@code XLARGE}). Accepting a number or a free string here would mean maintaining a second
 * vocabulary for the same column, so an unrecognised value is a {@code 400} with a field error.
 * The types are the enums themselves rather than {@code String}, which is what makes that
 * rejection automatic.
 *
 * <p>There is no {@code aiEnabled} field even though the column exists: UC-27 covers appearance
 * and text size, and the AI toggle belongs to UC-08 and UC-17 in a later module.
 */
@Schema(description = "The display preferences to change. Omit a field to leave it as it is (UC-27).")
public record UpdatePreferencesRequest(

        @Schema(description = "Appearance preference. SYSTEM follows the operating system.",
                example = "SYSTEM", nullable = true)
        ThemePreference themePreference,

        @Schema(description = "Text size preference.", example = "MEDIUM", nullable = true)
        FontScale fontScale) {
}
