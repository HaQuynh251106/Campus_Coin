package com.campuscoin.admin.entity;

import java.time.LocalDateTime;

import com.campuscoin.auth.entity.AccountStatus;
import com.campuscoin.auth.entity.UserRole;

public record AdminUserRow(
        Long id,
        String email,
        String fullName,
        UserRole role,
        AccountStatus status,
        String academicYear,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt) {
}
