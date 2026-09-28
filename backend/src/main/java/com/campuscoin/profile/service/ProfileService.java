package com.campuscoin.profile.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.entity.User;
import com.campuscoin.auth.repository.UserRepository;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.profile.dto.ProfileResponse;
import com.campuscoin.profile.dto.UpdatePreferencesRequest;
import com.campuscoin.profile.dto.UpdateProfileRequest;
import com.campuscoin.profile.mapper.ProfileMapper;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserRepository userRepository;
    private final ProfileMapper profileMapper;

    public ProfileService(UserRepository userRepository, ProfileMapper profileMapper) {
        this.userRepository = userRepository;
        this.profileMapper = profileMapper;
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(AuthenticatedUser principal) {
        return profileMapper.toProfile(requireCaller(principal));
    }

    @Transactional
    public ProfileResponse updateProfile(AuthenticatedUser principal, UpdateProfileRequest request) {
        User user = requireCaller(principal);

        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim());
        }
        if (request.academicYear() != null) {

            String academicYear = request.academicYear().trim();
            user.setAcademicYear(academicYear.isEmpty() ? null : academicYear);
        }
        if (request.monthlyAllowanceBaseline() != null) {
            user.setMonthlyAllowanceBaseline(request.monthlyAllowanceBaseline());
        }
        if (request.monthlySavingsGoal() != null) {
            user.setMonthlySavingsGoal(request.monthlySavingsGoal());
        }

        log.info("Profile updated userId={}", user.getId());
        return profileMapper.toProfile(user);
    }

    @Transactional
    public ProfileResponse updatePreferences(AuthenticatedUser principal,
                                             UpdatePreferencesRequest request) {
        User user = requireCaller(principal);

        if (request.themePreference() != null) {
            user.setThemePreference(request.themePreference());
        }
        if (request.fontScale() != null) {
            user.setFontScale(request.fontScale());
        }

        log.info("Preferences updated userId={}", user.getId());
        return profileMapper.toProfile(user);
    }

    private User requireCaller(AuthenticatedUser principal) {
        return userRepository.findById(principal.userId())
                .orElseThrow(() -> new NotFoundException("Your account could not be found."));
    }
}
