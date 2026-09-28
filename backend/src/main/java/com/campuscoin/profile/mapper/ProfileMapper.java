package com.campuscoin.profile.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.auth.entity.User;
import com.campuscoin.profile.dto.ProfileResponse;

@Component
public class ProfileMapper {

    public ProfileResponse toProfile(User user) {
        return new ProfileResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getAcademicYear(),
                user.getMonthlyAllowanceBaseline(),
                user.getMonthlySavingsGoal(),
                user.getCurrency(),
                user.getThemePreference(),
                user.getFontScale());
    }
}
