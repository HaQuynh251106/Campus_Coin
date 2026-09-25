package com.campuscoin.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * Hashing helpers for the two secrets this module handles.
 *
 * <p>Session tokens are stored as a SHA-256 hex digest because
 * {@code user_sessions.session_token_hash} and {@code refresh_token_hash} are
 * {@code CHAR(64) ASCII ascii_bin} - 64 hex characters, compared byte for byte. The reset token
 * hash is produced by the database itself ({@code sp_create_password_reset_token} applies
 * {@code SHA2(..., 256)}), so this class only needs the same representation for pre-checks.
 *
 * <p>SHA-256 without a salt is correct here and is not a weakness: these are high-entropy random
 * values, not passwords, so there is no dictionary to attack. Passwords go through bcrypt
 * instead, in the services.
 *
 * <p>Nothing in this class logs or exposes its input. The caller must treat the raw token as
 * secret and never write it to a log line.
 */
@Component
public class TokenHashService {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** SHA-256 of the given value, lower-case hex, 64 characters - the width the columns store. */
    public String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform, so this cannot happen on a valid JRE.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Creates a URL-safe random token with 256 bits of entropy, matching the strength the
     * database's reset procedure expects its callers to provide.
     *
     * <p>The result is the raw token: it goes into the reset link, and only its hash is stored.
     */
    public String newSecretToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }
}
