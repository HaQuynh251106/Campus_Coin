package com.campuscoin.profile.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.auth.entity.User;
import com.campuscoin.profile.dto.ProfileResponse;

/**
 * Maps a {@link User} row to the profile API model (UC-04, UC-27).
 *
 * <p>Kept as its own class, and as a plain class rather than a mapping library, for the same
 * reason {@code AuthMapper} is: it is the one place that decides which columns may leave the
 * server. The full {@link User} entity carries {@code password_hash} and {@code token_version},
 * so returning it from a controller would publish security material; a mapping this small is
 * cheaper to read than to generate.
 */
@Component
public class ProfileMapper {

    /**
     * UC-04 and UC-27: the student's own profile.
     *
     * <p>Deliberately omitted: {@code password_hash}, {@code token_version}, {@code role},
     * {@code status}, {@code last_login_at}, {@code email_verified_at} and {@code ai_enabled}.
     * None is part of the profile screen, and the first two must never be serialised.
     */
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
