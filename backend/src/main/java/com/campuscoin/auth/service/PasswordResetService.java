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

/**
 * The three steps of UC-03, backed entirely by the database's own token machinery.
 *
 * <p>{@code sp_create_password_reset_token}, {@code sp_verify_password_reset_token} and
 * {@code sp_complete_password_reset} already implement issuance, the read-only pre-check, the
 * one-time consume, the expiry rule and the post-reset revocation of every session with a
 * {@code token_version} bump (BR-03, BR-04). This service sequences those calls and hashes what
 * needs hashing; it deliberately owns none of those rules, so there is one definition of when a
 * reset token is usable and it lives in the database.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /**
     * UC-03 B3: the exact wording the requirement fixes. It is returned whether or not the
     * address exists, so the endpoint answers the same for every request and cannot be used to
     * find out which addresses are registered (UC-03 A2).
     */
    static final String GENERIC_REQUEST_MESSAGE =
            "If the email exists, a reset link has been sent.";

    /** UC-03 B7 confirmation. Says nothing about the account, only that the token was accepted. */
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

    /**
     * Issues a reset link (UC-03 B2).
     *
     * <p>Always answers with {@link #GENERIC_REQUEST_MESSAGE}, including for an unknown address
     * (UC-03 A2) and when delivery fails: any difference in the response would reveal whether the
     * account exists.
     *
     * <p>The raw token exists only in this method. The database is given its SHA-256 hash and
     * only the hash is stored, the link carries the raw value to the account owner, and neither
     * the token nor the link is ever logged (section 7.6).
     */
    @Transactional
    public String requestReset(PasswordResetRequestBody request, String ipAddress) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        loginAttemptService.assertResetAllowed(email);
        loginAttemptService.recordResetRequest(email);

        userRepository.findByEmail(email).ifPresent(user -> {
            String rawToken = tokenHashService.newSecretToken();
            resetDao.createResetToken(user.getId(), tokenHashService.sha256Hex(rawToken), ipAddress);
            notifier.sendPasswordResetLink(user.getEmail(), linkBuilder.build(rawToken));
            // The account id is useful for audit; the token is not written here.
            log.info("Password reset token issued userId={}", user.getId());
        });

        // Counted for known and unknown addresses alike, and the same message returned for both.
        return GENERIC_REQUEST_MESSAGE;
    }

    /**
     * Checks a token before the new-password screen opens (UC-03 B5).
     *
     * <p>Delegates to {@code sp_verify_password_reset_token}, which validates existence, one-time
     * use and expiry without consuming the token - a student who opens the link and closes the
     * tab must still be able to use it.
     *
     * <p>Not marked {@code readOnly}, because the check is a {@code CALL} and MySQL treats any
     * {@code CALL} as potentially writing, refusing it on a read-only connection. The procedure
     * itself is read-only; see {@link PasswordResetProcedureDao#findUserIdByValidToken}.
     *
     * @throws InvalidResetTokenException if the token is unknown, used or expired
     */
    @Transactional
    public PasswordResetVerifyResponse verifyToken(PasswordResetTokenRequest request) {
        Long userId = resetDao.findUserIdByValidToken(tokenHash(request.token()));
        if (userId == null) {
            throw new InvalidResetTokenException();
        }
        // A valid token may belong to a disabled account; the reset itself would succeed and
        // still not allow a sign-in. Refusing here would be a different rule than the database
        // applies, so the check is left to the sign-in path, where BR-03 already enforces it.
        return PasswordResetVerifyResponse.VALID;
    }

    /**
     * Sets the new password (UC-03 B7, BR-04).
     *
     * <p>Transactional because the requirement's own procedure expects the whole reset to be one
     * unit: if a later step failed, the rollback must also restore the token, so a link is never
     * burned by a reset that did not happen. Inside that transaction the database consumes the
     * token, changes the password, bumps {@code token_version} and revokes every open session
     * (BR-03) - so a student who resets their password is signed out everywhere at once.
     *
     * @throws RequestValidationException if the two passwords differ
     * @throws InvalidResetTokenException if the database refuses the token
     */
    @Transactional
    public String completeReset(PasswordResetCompleteRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new RequestValidationException("Request validation failed.",
                    List.of(new ApiError.FieldError("confirmPassword", "Passwords do not match.")));
        }

        resetDao.completeReset(
                tokenHash(request.token()),
                passwordEncoder.encode(request.newPassword()));

        // No user id or token is logged; the successful completion is enough for an audit trail.
        log.info("Password reset completed");
        return COMPLETE_MESSAGE;
    }

    /** SHA-256 hex, the only representation of a reset token this application handles. */
    private String tokenHash(String rawToken) {
        return tokenHashService.sha256Hex(rawToken.trim());
    }
}
