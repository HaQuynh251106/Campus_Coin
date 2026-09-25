package com.campuscoin.auth.dto;

import com.campuscoin.auth.entity.UserRole;

/**
 * The signed-in account as the Angular client needs it (UC-02 B2).
 *
 * <p>Five fields and no more. Nothing security-related is here: no {@code password_hash}, no
 * {@code token_version}, no session or refresh token, no account status, no internal identifier
 * beyond the id the client must send back. The entity is never serialised directly - this record
 * is what stands between the persistence model and the response, so a column added to
 * {@code users} later cannot leak by accident.
 */
public record UserSummaryResponse(
        Long id,
        String fullName,
        String email,
        UserRole role) {
}
