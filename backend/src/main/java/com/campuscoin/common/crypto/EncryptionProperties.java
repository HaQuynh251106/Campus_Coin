package com.campuscoin.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code campuscoin.encryption} block of {@code application.yml}.
 *
 * <p><b>This is a symmetric secret key, not a private key.</b> AES-256-GCM uses one secret for
 * both directions, so the key half of an asymmetric pair does not exist here and the code and
 * documentation must not call it one.
 *
 * @param key        the AES-256 secret, Base64-encoded, supplied through the
 *                   {@code CAMPUSCOIN_ENCRYPTION_KEY} environment variable. There is deliberately
 *                   no default: an application that started without a key would either write
 *                   plaintext where ciphertext is expected or invent a predictable key. Must
 *                   decode to exactly 32 bytes, which is what AES-256 requires.
 * @param keyVersion the identifier written into every envelope this process produces. It exists so
 *                   a future key rotation can decrypt old rows with the old key and new rows with
 *                   the new one; the envelope records which key produced it. Rotation itself is not
 *                   implemented - see the blocker in {@code docs/OVERNIGHT_BLOCKERS.md}.
 */
@ConfigurationProperties(prefix = "campuscoin.encryption")
public record EncryptionProperties(String key, Integer keyVersion) {
}
