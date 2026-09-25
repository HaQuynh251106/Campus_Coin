package com.campuscoin.auth.entity;

/**
 * Mirrors the {@code user_sessions.revoked_reason} ENUM.
 *
 * <p>Only {@link #LOGOUT} is set by this module. The rest exist in the schema for the modules
 * that own them: {@code PASSWORD_RESET} is written by {@code sp_complete_password_reset} and
 * {@code ADMIN_DISABLE} by {@code sp_set_user_status}, both in the database. They are declared
 * here so the column maps faithfully and no value is silently lost when a row is read back.
 */
public enum RevokedReason {
    LOGOUT,
    LOGOUT_ALL,
    ADMIN_DISABLE,
    PASSWORD_RESET,
    EXPIRED,
    REPLACED
}
