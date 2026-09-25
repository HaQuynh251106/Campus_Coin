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

/**
 * Registration and sign-in for both portals (UC-01, UC-02, UC-05).
 *
 * <p>One service serves the student and the administrator endpoints. UC-05 requires the
 * administrator portal to be separate, and it is - a different URL in a different controller,
 * with a different expected role - but the authentication logic behind it is the same, so it is
 * written once. Only the expected role and the resulting policy differ, and both are parameters
 * of {@link #login}, not copies of this method.
 *
 * <p>What the database owns, and this class therefore does not repeat: the uniqueness of
 * {@code users.email} (BR-01, {@code uk_users_email}) and the authoritative status of the
 * session and reset data. What is genuinely the application's: hashing the password, minting
 * the token, and recording the session and the sign-in time.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Used when {@code app.currency} is missing, so registration can never fail on a setting. */
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

    /**
     * Registers a new student (UC-01).
     *
     * <p>Transactional because three things must happen together: the account row, and - once
     * the caller signs in - nothing else. The account insert and the duplicate-email check are
     * the whole unit; if the insert is rejected by the unique key there must be no half-created
     * account.
     *
     * <p>No session is opened and no token is returned. UC-01 B6 sends the new student to
     * sign-in, so registration deliberately does not authenticate - returning a token here would
     * be inventing a flow the use case does not describe.
     *
     * <p>The password is hashed with bcrypt before it touches the entity, and the plain value is
     * never stored, logged or included in an exception message (BR-01, section 7.2).
     *
     * @throws RequestValidationException if the two passwords differ
     * @throws EmailAlreadyRegisteredException if the address is taken (UC-01 A1, UAT-01)
     */
    @Transactional
    public void register(RegisterRequest request) {
        assertPasswordsMatch(request.password(), request.confirmPassword(),
                "confirmPassword", "Passwords do not match.");

        String email = normaliseEmail(request.email());

        // A pre-check gives the intended UC-01 A1 error for the ordinary case. It is not the only
        // defence: the unique key is still the authority, and a race that slips past this check
        // is caught below and answered with the same error.
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
            // uk_users_email. The driver message is not echoed; the log line carries the cause.
            log.info("Registration rejected by the unique email constraint", ex);
            throw new EmailAlreadyRegisteredException();
        }

        // Nothing else is created. UC-01 B5 gives a new student the shared default category set -
        // the categories rows with user_id IS NULL - which already exist, so inserting per-user
        // copies here would contradict the requirement and duplicate data.
        log.info("Student registered userId={}", student.getId());
    }

    /**
     * Authenticates a student or an administrator and opens a session (UC-02, UC-05).
     *
     * <p>Order of checks, and why:
     * <ol>
     *   <li>The throttle, so a locked-out address costs no bcrypt work (section 7.10).</li>
     *   <li>The account. An unknown address produces the same error as a wrong password, so the
     *       endpoint cannot be used to enumerate accounts (UC-02 A1).</li>
     *   <li>The password. Its result is not revealed until the role check has also passed.</li>
     *   <li>The account status (UC-02 A2, BR-03). Checked after the password because telling a
     *       stranger that an address is disabled would leak that the address exists - only
     *       someone who already knows the credentials may be told.</li>
     *   <li>The expected role (UC-05 A1, E1). Also after the password, for the same reason: a
     *       student must not be able to find out from an error message that an address is an
     *       administrator account.</li>
     * </ol>
     *
     * @param request      credentials from the request body
     * @param expectedRole the role this endpoint serves; {@code STUDENT} for the student portal,
     *                     {@code ADMIN} for the administrator portal
     * @param ipAddress    client address, recorded on the session
     * @param userAgent    client user agent, recorded on the session
     * @throws InvalidCredentialsException if the address is unknown or the password is wrong
     * @throws AccountDisabledException    if the account is DISABLED
     * @throws ForbiddenException          if the account's role is not {@code expectedRole}
     */
    @Transactional
    public AuthResponse login(LoginRequest request, UserRole expectedRole,
                              String ipAddress, String userAgent) {
        String email = normaliseEmail(request.email());
        loginAttemptService.assertLoginAllowed(email);

        Optional<User> found = userRepository.findByEmail(email);
        if (found.isEmpty()) {
            // Counted as a failure so the throttle is identical for a real and a fictional
            // address; otherwise timing or a 429 after five tries would reveal which is which.
            loginAttemptService.recordLoginFailure(email);
            throw new InvalidCredentialsException();
        }

        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordLoginFailure(email);
            throw new InvalidCredentialsException();
        }

        // Only credential failures are counted. Someone who supplied the correct password is not
        // brute-forcing, and counting these would lock a student out of their own account for
        // opening the wrong portal by mistake.
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

        // The role signed in is recorded, but never the credentials or the token.
        log.info("Sign-in succeeded userId={} role={}", user.getId(), user.getRole());

        return AuthResponse.of(issued.token(), expiresInSeconds, authMapper.toUserSummary(user));
    }

    /**
     * Ends the session that made this request (UC-02 B5).
     *
     * <p>The identity comes from the verified token, never from the request body: a client must
     * not be able to sign out, or avoid signing out, another account.
     */
    @Transactional
    public void logout(AuthenticatedUser principal) {
        sessionService.revokeCurrentSession(principal);
    }

    /**
     * Session lifetime from {@code auth.session_ttl_minutes} (UC-02 B3). VĐ-05 makes it
     * administrator-editable, so it is read rather than hard-coded, with the configured value as
     * the fallback for a missing row.
     */
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

    /**
     * BR-01: the login email is unique and compared case-insensitively by the column collation.
     * Normalising before both the lookup and the insert keeps a single representation in the
     * table, so "Alex@..." and "alex@..." cannot become two accounts.
     */
    private String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
