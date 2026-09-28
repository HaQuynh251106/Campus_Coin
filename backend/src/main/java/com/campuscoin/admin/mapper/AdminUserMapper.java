package com.campuscoin.admin.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.campuscoin.admin.dto.AdminUserResponse;
import com.campuscoin.admin.entity.AdminUserRow;

@Component
public class AdminUserMapper {

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

    public List<AdminUserResponse> toResponses(List<AdminUserRow> rows) {
        return rows.stream().map(this::toResponse).toList();
    }
}
