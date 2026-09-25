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

/**
 * A row of {@code user_sessions}.
 *
 * <p>This is the "secure session" of UC-02 B3. The database does not create or revoke sessions
 * for this flow - no stored procedure writes to this table - so issuing a session on sign-in and
 * revoking it on sign-out is the application's job.
 *
 * <p>Only the SHA-256 hash of the JWT is stored, never the token itself, and the table's
 * {@code uk_sessions_token} unique key makes the hash the lookup key. A stolen database
 * therefore yields no usable token.
 *
 * <p>{@code refresh_token_hash} is deliberately NOT mapped. UC-02 and UC-03 define no token
 * refresh flow, and the agreed endpoint list excludes one, so the column stays NULL. Mapping a
 * field nothing reads or writes would imply a feature that does not exist.
 */
@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** {@code CHAR(64) ascii_bin}: the column definition mirrors the schema for validation. */
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
        // Required by JPA.
    }

    /**
     * Opens a session for a successful sign-in.
     *
     * @param userId           the account the session belongs to
     * @param sessionTokenHash SHA-256 hex of the JWT that was just issued
     * @param ipAddress        client address, truncated to the column's 45 characters
     * @param userAgent        client user agent, truncated to the column's 255 characters
     * @param issuedAt         session start, on the database clock
     * @param expiresAt        session end, derived from {@code auth.session_ttl_minutes}
     */
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

    /** True when the session is still usable: not revoked and not past its expiry. */
    public boolean isLive(LocalDateTime now) {
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }

    public void revoke(RevokedReason reason, LocalDateTime when) {
        this.revokedAt = when;
        this.revokedReason = reason;
    }

    /** The columns are VARCHAR with a fixed width; an over-long header would fail the insert. */
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
