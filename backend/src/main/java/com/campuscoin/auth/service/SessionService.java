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

        user.setLastLoginAt(issuedAt);
        userRepository.save(user);
    }

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
