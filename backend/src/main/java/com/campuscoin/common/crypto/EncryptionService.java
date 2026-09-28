package com.campuscoin.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EncryptionService {

    private static final byte ENVELOPE_FORMAT = 1;

    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Logger LOGGER = LoggerFactory.getLogger(EncryptionService.class);

    private static final AtomicBoolean WARNED_LEGACY = new AtomicBoolean(false);

    private final SecretKeySpec key;
    private final int keyVersion;

    public EncryptionService(EncryptionProperties properties) {
        this.key = decodeKey(properties.key());
        this.keyVersion = properties.keyVersion() == null ? 1 : properties.keyVersion();
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer envelope = ByteBuffer.allocate(2 + IV_LENGTH + sealed.length);
            envelope.put(ENVELOPE_FORMAT);
            envelope.put((byte) keyVersion);
            envelope.put(iv);
            envelope.put(sealed);
            return Base64.getEncoder().encodeToString(envelope.array());
        } catch (GeneralSecurityException e) {

            throw new EncryptionException("Failed to encrypt a value.", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        byte[] envelope;
        try {
            envelope = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException e) {

            throw new EncryptionException("Stored value is not a valid encrypted envelope.", e);
        }
        if (envelope.length < 2 + IV_LENGTH + 1) {
            throw new EncryptionException("Stored value is too short to be an encrypted envelope.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        byte format = buffer.get();
        byte version = buffer.get();
        if (format != ENVELOPE_FORMAT) {
            throw new EncryptionException("Stored value uses an unsupported envelope format.");
        }
        if (version != (byte) keyVersion) {

            throw new EncryptionException("Stored value was encrypted with a different key version.");
        }
        byte[] iv = new byte[IV_LENGTH];
        buffer.get(iv);
        byte[] sealed = new byte[buffer.remaining()];
        buffer.get(sealed);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {

            throw new EncryptionException("Stored value failed authentication.", e);
        }
    }

    public String decryptStored(String stored) {
        if (stored == null) {
            return null;
        }
        if (!isEnvelope(stored)) {
            if (WARNED_LEGACY.compareAndSet(false, true)) {
                LOGGER.warn("A non-encrypted value was read from an encrypted column and is being "
                        + "returned as-is. This means the database still holds plaintext written "
                        + "before application-level field encryption was enabled; it disappears as "
                        + "those rows are rewritten. No value is logged.");
            }
            return stored;
        }
        return decrypt(stored);
    }

    private static boolean isEnvelope(String stored) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return decoded.length >= 2 + IV_LENGTH + 1 && decoded[0] == ENVELOPE_FORMAT;
    }

    public String encryptAmount(java.math.BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return encrypt(amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    public java.math.BigDecimal decryptAmount(String stored) {
        if (stored == null) {
            return null;
        }
        String plaintext = decrypt(stored);
        try {
            return new java.math.BigDecimal(plaintext).setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException e) {

            throw new EncryptionException("Stored value did not decrypt to a monetary amount.", e);
        }
    }

    private static SecretKeySpec decodeKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "campuscoin.encryption.key is not set. Provide CAMPUSCOIN_ENCRYPTION_KEY - a "
                            + "Base64-encoded 32-byte AES-256 secret. Generate one with: "
                            + "openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "campuscoin.encryption.key is not valid Base64. It must be the Base64 encoding "
                            + "of exactly 32 random bytes (openssl rand -base64 32).");
        }
        if (decoded.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException(
                    "campuscoin.encryption.key must decode to 32 bytes for AES-256, but decoded to "
                            + decoded.length + " bytes.");
        }
        return new SecretKeySpec(decoded, "AES");
    }
}
