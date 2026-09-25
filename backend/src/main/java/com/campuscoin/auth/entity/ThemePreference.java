package com.campuscoin.auth.entity;

/**
 * Mirrors the {@code users.theme_pref} ENUM. UC-27 lets the student choose the appearance of the
 * application; {@code SYSTEM} defers to the operating system's own setting.
 *
 * <p>Lives in this package rather than in the profile module because {@link User} declares a field
 * of this type, and the profile module already depends on this package. Putting it here keeps the
 * dependency one-way - an enum placed under {@code profile} would make the authentication module
 * depend on the profile module, which is the wrong direction for a column of {@code users}.
 */
public enum ThemePreference {
    LIGHT,
    DARK,
    SYSTEM
}
