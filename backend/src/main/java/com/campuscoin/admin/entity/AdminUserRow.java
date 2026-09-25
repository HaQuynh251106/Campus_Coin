package com.campuscoin.admin.entity;

import java.time.LocalDateTime;

import com.campuscoin.auth.entity.AccountStatus;
import com.campuscoin.auth.entity.UserRole;

/**
 * One row of the administrator's user list (UC-22 B1).
 *
 * <p><b>The record's component list is the disclosure decision, made where a reviewer can see all of
 * it.</b> A projection carries only the columns the query selected, so a field that is not here
 * cannot reach a response however the mapper is later changed. That is why {@code password_hash} and
 * {@code token_version} are absent as components rather than merely absent from the SQL: either
 * would be a fault, and {@code token_version} is the subtler of the two - it is the entire security
 * meaning of a JWT's {@code tv} claim, so publishing it would tell an attacker whether a stolen token
 * is still live.
 *
 * <p>Also deliberately absent, for want of a use case rather than for safety:
 * {@code monthly_allowance_baseline} and {@code monthly_savings_goal} (VĐ-04 - the student's own
 * financial position, which UC-22 does not ask an administrator to see), {@code theme_pref},
 * {@code font_scale} and {@code ai_enabled} (UC-27 preferences), {@code email_verified_at}, and
 * {@code updated_at}.
 *
 * <p>{@code lastLoginAt} is carried because UC-23's "active users" figure is about recency of use,
 * and the per-account value is what makes that figure legible beside a list of accounts.
 *
 * <p>{@code email} is carried because the administrator screens list and search by it and because
 * the reset flow (UC-22 B4) is addressed to it. This is the one place in the API where another
 * account's address is published, and it is reachable only under {@code hasRole("ADMIN")}.
 */
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
