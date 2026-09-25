package com.campuscoin.auth.entity;

/**
 * Mirrors the {@code users.font_scale} ENUM. UC-27 exposes a text-size control with four steps;
 * {@code MEDIUM} is the schema default.
 *
 * <p>Kept beside {@link User} for the same reason as {@link ThemePreference}: the entity maps a
 * column of this type, and the profile module depends on this package rather than the reverse.
 */
public enum FontScale {
    SMALL,
    MEDIUM,
    LARGE,
    XLARGE
}
