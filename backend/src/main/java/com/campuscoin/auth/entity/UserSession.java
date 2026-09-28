package com.campuscoin.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_token_hash", nullable = false,
            columnDefinition = "char(64) character set ascii collate ascii_bin")
    private String sessionTokenHash;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason",
            columnDefinition = "enum('LOGOUT','LOGOUT_ALL','ADMIN_DISABLE','PASSWORD_RESET','EXPIRED','REPLACED')")
    private RevokedReason revokedReason;

    protected UserSession() {

    }

    public static UserSession open(Long userId, String sessionTokenHash, String ipAddress,
                                   String userAgent, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        UserSession session = new UserSession();
        session.userId = userId;
        session.sessionTokenHash = sessionTokenHash;
        session.ipAddress = truncate(ipAddress, 45);
        session.userAgent = truncate(userAgent, 255);
        session.issuedAt = issuedAt;
        session.lastSeenAt = issuedAt;
        session.expiresAt = expiresAt;
        return session;
    }

    public boolean isLive(LocalDateTime now) {
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }

    public void revoke(RevokedReason reason, LocalDateTime when) {
        this.revokedAt = when;
        this.revokedReason = reason;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSessionTokenHash() {
        return sessionTokenHash;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public RevokedReason getRevokedReason() {
        return revokedReason;
    }
}
