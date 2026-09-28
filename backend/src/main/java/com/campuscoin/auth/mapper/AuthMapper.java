package com.campuscoin.auth.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.auth.dto.UserSummaryResponse;
import com.campuscoin.auth.entity.User;

@Component
public class AuthMapper {

    public UserSummaryResponse toUserSummary(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole());
    }
}
