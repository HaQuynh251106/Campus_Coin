package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.AdminUserResponse;
import com.campuscoin.admin.entity.AdminUserRow;

/**
 * Maps an account row to the API model (UC-22 B1).
 *
 * <p><b>The widening risk this class guards against is the reverse of the usual one.</b>
 * {@link AdminUserRow} already carries only the published columns - {@code passwordHash} and
 * {@code tokenVersion} are not components of it - so this class cannot leak a secret through
 * forgetting to omit one. What it could do is grow: a later field added to the row would appear in
 * responses the moment it was added, because the mapping here is a straight pass-through. That is why
 * the response's field list is asserted literally in {@code AdminUserApiIT}: the test, not this
 * class, is what makes an addition deliberate.
 *
 * <p>A plain {@code @Component} rather than a mapping library, matching {@code CategoryMapper} and
 * {@code ProfileMapper}: the point is that the disclosure decision is readable on one screen.
 */
@Component
public class AdminUserMapper {

    /**
     * UC-22 B1: one account as the administrator's list shows it.
     *
     * <p>{@code academicYear} and {@code lastLoginAt} pass through as null when the account has not
     * set or done the thing - {@code AdminUserResponse} marks both {@code NON_NULL}, so an absent
     * value is omitted rather than sent as {@code null}. That is the convention the profile and
     * category responses use for their nullable columns, so a client reads one rule everywhere.
     */
    public AdminUserResponse toResponse(AdminUserRow row) {
        return new AdminUserResponse(
                row.id(),
                row.email(),
                row.fullName(),
                row.role(),
                row.status(),
                row.academicYear(),
                row.lastLoginAt(),
                row.createdAt());
    }

    /** UC-22 B1: the whole list, mapped row by row through {@link #toResponse}. */
    public List<AdminUserResponse> toResponses(List<AdminUserRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }
}
