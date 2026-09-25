package com.campuscoin.auth.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.entity.AccountStatus;
import com.campuscoin.auth.entity.RevokedReason;
import com.campuscoin.auth.entity.User;
import com.campuscoin.auth.entity.UserSession;
import com.campuscoin.auth.repository.UserRepository;
import com.campuscoin.auth.repository.UserSessionRepository;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.auth.security.JwtService;
import com.campuscoin.auth.security.TokenHashService;
import com.campuscoin.common.exception.UnauthenticatedException;

/**
 * Owns the {@code user_sessions} rows: creating one at sign-in, resolving one on every
 * authenticated request, and revoking one at sign-out.
 *
 * <p>The database has no procedure or trigger for this table, so it is one of the few pieces of
 * authentication logic that genuinely belongs to the application (see the responsibility table
 * in the plan). Everything it decides with is read from the database each time:
 *
 * <ul>
 *   <li>Session validity comes from the row's {@code revoked_at} and {@code expires_at}, so
 *       UC-02 A3 (expired session) and UC-02 B5 (signed-out session) are one check.</li>
 *   <li>Account validity comes from {@code users.status}, so a DISABLED account stops working
 *       even while its sessions are still open (BR-03).</li>
 *   <li>The {@code tv} claim is compared with the stored {@code token_version} on every request.
 *       BR-03 bumps the column on a password reset and on an account disable, which is what
 *       makes those changes take effect immediately rather than at token expiry.</li>
 * </ul>
 *
 * <p>Timestamps are taken from the database clock via {@code CURRENT_TIMESTAMP} defaults where
 * the schema provides them, and otherwise from the injected application clock compared against
 * the same zone the connection runs in ({@code +07:00}), so session expiry and the views that
 * bucket by day agree.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final UserSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final TokenHashService tokenHashService;
    private final JwtService jwtService;

    public SessionService(UserSessionRepository sessionRepository,
                          UserRepository userRepository,
                          TokenHashService tokenHashService,
                          JwtService jwtService) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.tokenHashService = tokenHashService;
        this.jwtService = jwtService;
    }

    /**
     * Records the session opened by a successful sign-in (UC-02 B3).
     *
     * <p>The JWT is not stored. Its SHA-256 hash is, because {@code uk_sessions_token} makes the
     * hash the lookup key for later requests and a leaked database table then contains nothing
     * that can be replayed as a token. {@code refresh_token_hash} is left NULL: no use case
     * defines a refresh flow.
     *
     * @param user      the account that just authenticated
     * @param token     the access token that was issued to it
     * @param expiresAt the token's expiry, so session and token end together
     * @param ipAddress client address as seen by the server
     * @param userAgent raw {@code User-Agent} header, truncated by the entity
     */
    @Transactional
    public void openSession(User user, String token, Instant expiresAt,
                            String ipAddress, String userAgent) {
        LocalDateTime issuedAt = LocalDateTime.ofInstant(Instant.now(), ZoneId.of("+07:00"));
        LocalDateTime expires = LocalDateTime.ofInstant(expiresAt, ZoneId.of("+07:00"));

        sessionRepository.save(UserSession.open(
                user.getId(),
                tokenHashService.sha256Hex(token),
                ipAddress,
                userAgent,
                issuedAt,
                expires));

        // Feeds UC-23 "active in the last 30 days". No procedure updates this column, so the
        // application must.
        user.setLastLoginAt(issuedAt);
        userRepository.save(user);
    }

    /**
     * Verifies a bearer token and resolves it to the caller's identity.
     *
     * <p>Called by the token filter on every authenticated request. Verification order is
     * deliberate: the cheap cryptographic check first, then the two database checks, so an
     * unsigned or expired token never costs a query.
     *
     * @throws com.campuscoin.common.exception.UnauthenticatedException if the token or the
     *         session behind it is not usable, or the account is no longer allowed to sign in
     */
    @Transactional(readOnly = true)
    public AuthenticatedUser authenticateToken(String token) {
        JwtService.JwtClaims claims = jwtService.verify(token);

        UserSession session = sessionRepository
                .findBySessionTokenHash(tokenHashService.sha256Hex(token))
                .orElseThrow(() -> new UnauthenticatedException(
                        "The access token is invalid or has expired."));

        LocalDateTime now = LocalDateTime.now(ZoneId.of("+07:00"));
        if (!session.isLive(now)) {
            throw new UnauthenticatedException("Your session has ended. Please sign in again.");
        }

        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new UnauthenticatedException(
                        "The access token is invalid or has expired."));

        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new UnauthenticatedException(
                    "This account is currently disabled. Please contact the administrator.");
        }

        // BR-03: a mismatch means every token of this account was invalidated after this one
        // was issued - by a password reset or an account disable.
        if (!user.getTokenVersion().equals(claims.tokenVersion())) {
            throw new UnauthenticatedException("Your session has ended. Please sign in again.");
        }

        return new AuthenticatedUser(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                session.getSessionTokenHash());
    }

    /**
     * Revokes the session that made this request (UC-02 B5).
     *
     * <p>Idempotent: signing out an already-revoked session is not an error, because a client
     * that retries a failed logout should not be told something went wrong. Only this one session
     * is affected - signing out on one device must not sign the student out everywhere.
     */
    @Transactional
    public void revokeCurrentSession(AuthenticatedUser principal) {
        sessionRepository.findBySessionTokenHash(principal.sessionTokenHash())
                .filter(session -> session.getRevokedAt() == null)
                .ifPresent(session -> {
                    session.revoke(RevokedReason.LOGOUT,
                            LocalDateTime.now(ZoneId.of("+07:00")));
                    sessionRepository.save(session);
                    log.debug("Session revoked userId={}", principal.userId());
                });
    }
}
