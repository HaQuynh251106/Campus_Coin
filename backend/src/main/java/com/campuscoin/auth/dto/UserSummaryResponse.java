package com.campuscoin.auth.dto;

import com.campuscoin.auth.entity.UserRole;

public record UserSummaryResponse(
        Long id,
        String fullName,
        String email,
        UserRole role) {
}
