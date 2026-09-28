package com.campuscoin.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.dto.AuthResponse;
import com.campuscoin.auth.dto.LoginRequest;
import com.campuscoin.auth.dto.RegisterRequest;
import com.campuscoin.auth.dto.UserSummaryResponse;
import com.campuscoin.auth.entity.AccountStatus;
import com.campuscoin.auth.entity.User;
import com.campuscoin.auth.entity.UserRole;
import com.campuscoin.auth.mapper.AuthMapper;
import com.campuscoin.auth.repository.UserRepository;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.auth.security.JwtService;
import com.campuscoin.common.exception.AccountDisabledException;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.EmailAlreadyRegisteredException;
import com.campuscoin.common.exception.ForbiddenException;
import com.campuscoin.common.exception.InvalidCredentialsException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.common.setting.SettingReader;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String DEFAULT_CURRENCY = "USD";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final LoginAttemptService loginAttemptService;
    private final SettingReader settingReader;
    private final AuthMapper authMapper;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       SessionService sessionService,
                       LoginAttemptService loginAttemptService,
                       SettingReader settingReader,
                       AuthMapper authMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.loginAttemptService = loginAttemptService;
        this.settingReader = settingReader;
        this.authMapper = authMapper;
    }

    @Transactional
    public void register(RegisterRequest request) {
        assertPasswordsMatch(request.password(), request.confirmPassword(),
                "confirmPassword", "Passwords do not match.");

        String email = normaliseEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            log.info("Registration rejected: email already registered");
            throw new EmailAlreadyRegisteredException();
        }

        String currency = settingReader.getString(SettingReader.APP_CURRENCY, DEFAULT_CURRENCY);
        User student = User.newStudent(
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                currency);

        try {
            userRepository.saveAndFlush(student);
        } catch (DataIntegrityViolationException ex) {

            log.info("Registration rejected by the unique email constraint", ex);
            throw new EmailAlreadyRegisteredException();
        }

        log.info("Student registered userId={}", student.getId());
    }

    @Transactional
    public AuthResponse login(LoginRequest request, UserRole expectedRole,
                              String ipAddress, String userAgent) {
        String email = normaliseEmail(request.email());
        loginAttemptService.assertLoginAllowed(email);

        Optional<User> found = userRepository.findByEmail(email);
        if (found.isEmpty()) {

            loginAttemptService.recordLoginFailure(email);
            throw new InvalidCredentialsException();
        }

        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordLoginFailure(email);
            throw new InvalidCredentialsException();
        }

        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountDisabledException();
        }

        if (user.getRole() != expectedRole) {
            throw new ForbiddenException(expectedRole == UserRole.ADMIN
                    ? "This portal is for administrators only."
                    : "Please use the administrator portal to sign in.");
        }

        loginAttemptService.recordLoginSuccess(email);

        int ttlMinutes = sessionTtlMinutes();
        Instant now = Instant.now();
        JwtService.IssuedToken issued = jwtService.issue(user, ttlMinutes, now);

        sessionService.openSession(user, issued.token(), issued.expiresAt(), ipAddress, userAgent);

        long expiresInSeconds = java.time.Duration.between(now, issued.expiresAt()).toSeconds();

        log.info("Sign-in succeeded userId={} role={}", user.getId(), user.getRole());

        return AuthResponse.of(issued.token(), expiresInSeconds, authMapper.toUserSummary(user));
    }

    @Transactional
    public void logout(AuthenticatedUser principal) {
        sessionService.revokeCurrentSession(principal);
    }

    private int sessionTtlMinutes() {
        return settingReader.getInt(SettingReader.AUTH_SESSION_TTL_MINUTES, 120);
    }

    private void assertPasswordsMatch(String password, String confirmation,
                                      String field, String message) {
        if (!password.equals(confirmation)) {
            throw new RequestValidationException("Request validation failed.",
                    List.of(new ApiError.FieldError(field, message)));
        }
    }

    private String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
