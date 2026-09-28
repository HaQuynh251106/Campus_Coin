package com.campuscoin.auth.service;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.dto.PasswordResetCompleteRequest;
import com.campuscoin.auth.dto.PasswordResetRequestBody;
import com.campuscoin.auth.dto.PasswordResetTokenRequest;
import com.campuscoin.auth.dto.PasswordResetVerifyResponse;
import com.campuscoin.auth.repository.PasswordResetProcedureDao;
import com.campuscoin.auth.repository.UserRepository;
import com.campuscoin.auth.security.PasswordResetNotifier;
import com.campuscoin.auth.security.PasswordResetLinkBuilder;
import com.campuscoin.auth.security.TokenHashService;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.InvalidResetTokenException;
import com.campuscoin.common.exception.RequestValidationException;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final String GENERIC_REQUEST_MESSAGE =
            "If the email exists, a reset link has been sent.";

    static final String COMPLETE_MESSAGE =
            "Your password has been reset. Please sign in with your new password.";

    private final UserRepository userRepository;
    private final PasswordResetProcedureDao resetDao;
    private final PasswordEncoder passwordEncoder;
    private final TokenHashService tokenHashService;
    private final PasswordResetNotifier notifier;
    private final PasswordResetLinkBuilder linkBuilder;
    private final LoginAttemptService loginAttemptService;

    public PasswordResetService(UserRepository userRepository,
                                PasswordResetProcedureDao resetDao,
                                PasswordEncoder passwordEncoder,
                                TokenHashService tokenHashService,
                                PasswordResetNotifier notifier,
                                PasswordResetLinkBuilder linkBuilder,
                                LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.resetDao = resetDao;
        this.passwordEncoder = passwordEncoder;
        this.tokenHashService = tokenHashService;
        this.notifier = notifier;
        this.linkBuilder = linkBuilder;
        this.loginAttemptService = loginAttemptService;
    }

    @Transactional
    public String requestReset(PasswordResetRequestBody request, String ipAddress) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        loginAttemptService.assertResetAllowed(email);
        loginAttemptService.recordResetRequest(email);

        userRepository.findByEmail(email).ifPresent(user -> {
            String rawToken = tokenHashService.newSecretToken();
            resetDao.createResetToken(user.getId(), tokenHashService.sha256Hex(rawToken), ipAddress);
            notifier.sendPasswordResetLink(user.getEmail(), linkBuilder.build(rawToken));

            log.info("Password reset token issued userId={}", user.getId());
        });

        return GENERIC_REQUEST_MESSAGE;
    }

    @Transactional
    public PasswordResetVerifyResponse verifyToken(PasswordResetTokenRequest request) {
        Long userId = resetDao.findUserIdByValidToken(tokenHash(request.token()));
        if (userId == null) {
            throw new InvalidResetTokenException();
        }

        return PasswordResetVerifyResponse.VALID;
    }

    @Transactional
    public String completeReset(PasswordResetCompleteRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new RequestValidationException("Request validation failed.",
                    List.of(new ApiError.FieldError("confirmPassword", "Passwords do not match.")));
        }

        resetDao.completeReset(
                tokenHash(request.token()),
                passwordEncoder.encode(request.newPassword()));

        log.info("Password reset completed");
        return COMPLETE_MESSAGE;
    }

    private String tokenHash(String rawToken) {
        return tokenHashService.sha256Hex(rawToken.trim());
    }
}
