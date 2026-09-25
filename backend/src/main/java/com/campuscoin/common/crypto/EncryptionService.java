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

/**
 * Application-level field encryption: AES-256-GCM over the values this application must not store
 * in the clear.
 *
 * <p><b>What this protects and what it does not.</b> It protects the <em>stored free text</em>: a
 * student's transaction and recurring-rule descriptions, and a bookmark note, are written to MySQL
 * as ciphertext, so a direct {@code SELECT} - through Adminer, a backup, a replica, or a stolen
 * data file - does not reveal them. It is <em>not</em> a substitute for TLS and it does not protect
 * the API process or the key: anything that can read the process environment can decrypt. Under the
 * threat model this is the intended trade - the database file is the thing most likely to leak, and
 * the application key is held separately from it. See {@code docs/SECURITY.md}.
 *
 * <p><b>Amounts are deliberately NOT encrypted, and that is a known gap.</b> MySQL cannot decrypt:
 * it has no AES-GCM, and putting the key in the database is forbidden. An encrypted
 * {@code amount} therefore could not be summed, compared or ordered by any view or stored
 * procedure, and eleven views and six procedures do exactly that. Encrypting it means moving the
 * whole reporting tier into the application first - a separate, separately-approved project,
 * recorded as OB-013. Until then a direct SELECT still reveals amounts, and {@code
 * docs/SECURITY.md} says so rather than implying otherwise.
 *
 * <p><b>Envelope format.</b> Every value is written as
 * {@code format(1) || keyVersion(1) || iv(12) || ciphertext+tag(n)}, then Base64-encoded so it
 * survives a {@code VARCHAR} column and a JSON export unchanged. The layout is fixed and
 * independent of any column type, so one format serves every encrypted field and a future rotation
 * can tell which key produced a row from the keyVersion byte alone.
 *
 * <p><b>Why a fresh random IV per call is the security-critical part.</b> GCM fails catastrophically
 * if the same key and IV pair is ever reused: the keystream repeats, and an attacker with two
 * ciphertexts under the same nonce recovers the XOR of their plaintexts and can forge a valid
 * authentication tag. The IV is therefore drawn from {@link SecureRandom} inside {@link #encrypt}
 * for every single value, never derived from the plaintext, the row id, or a counter, and never
 * accepted from a caller. {@code EncryptionServiceTest} asserts that two encryptions of an
 * identical plaintext differ in both IV and ciphertext.
 *
 * <p><b>Why GCM and not CBC.</b> GCM authenticates as well as encrypts, so
 * {@link #decrypt} throws on a value that was altered rather than returning attacker-chosen
 * plaintext; a later {@code amount} silently changed to an attacker's value is exactly the attack a
 * bare CBC would allow.
 *
 * <p>The key is never logged, never returned, never placed in a JWT, and never sent to MySQL.
 * Neither is any plaintext on the way through this class.
 */
@Service
public class EncryptionService {

    /** Enumeration of the envelope layout this build writes. Bumped only if the format changes. */
    private static final byte ENVELOPE_FORMAT = 1;

    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Logger LOGGER = LoggerFactory.getLogger(EncryptionService.class);

    /**
     * Whether the legacy-plaintext warning has already been emitted. Static so a transient service
     * instance cannot turn the warning into a per-request log flood, and atomic so two threads
     * racing on the first read still log it exactly once.
     */
    private static final AtomicBoolean WARNED_LEGACY = new AtomicBoolean(false);

    private final SecretKeySpec key;
    private final int keyVersion;

    public EncryptionService(EncryptionProperties properties) {
        this.key = decodeKey(properties.key());
        this.keyVersion = properties.keyVersion() == null ? 1 : properties.keyVersion();
    }

    /**
     * Encrypts one plaintext value.
     *
     * @param plaintext the value to protect; may be {@code null}
     * @return Base64 envelope, or {@code null} if {@code plaintext} was {@code null}. Null is
     *         passed through rather than encrypted because several of the columns this serves are
     *         legitimately nullable (a transaction with no description), and an encrypted empty
     *         string would be indistinguishable from "no value" only by accident. Keeping null
     *         null means "the student gave no description" survives a round trip exactly.
     * @throws EncryptionException if the cipher is unavailable, which would mean the JVM lacks a
     *                             required algorithm and no data can be written safely
     */
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
            // Deliberately does not include the plaintext or the key in the message.
            throw new EncryptionException("Failed to encrypt a value.", e);
        }
    }

    /**
     * Decrypts one stored envelope.
     *
     * @param stored the Base64 envelope written by {@link #encrypt}; may be {@code null}
     * @return the original plaintext, or {@code null} if {@code stored} was {@code null}
     * @throws EncryptionException if the value is not a well-formed envelope, or GCM authentication
     *                             fails - which means the ciphertext, IV or tag was altered, or the
     *                             row was written with a different key
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        byte[] envelope;
        try {
            envelope = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException e) {
            // Not Base64 at all: a plaintext value from before the migration, or a corrupted
            // column. Distinguished from a tag failure only in the log-safe internal message.
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
            // Rotation is not implemented yet: an old-version row cannot be read by this build.
            // Named explicitly so the failure points at the key rather than at corruption.
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
            // AEADBadTagException lands here: the value was tampered with or the key is wrong.
            throw new EncryptionException("Stored value failed authentication.", e);
        }
    }

    /**
     * Decrypts a value read from an encrypted column, tolerating a value that was never encrypted.
     *
     * <p>This is the method every read path uses; {@link #decrypt} is for callers that know the
     * value must be an envelope. The difference matters right now, at the transition:
     *
     * <ul>
     *   <li>Rows already in a database - and the demo rows in {@code db/06_demo.sql} - were written
     *       before this feature existed. They hold plaintext. SQL cannot produce an envelope: a
     *       {@code DEFAULT} column cannot call an application cipher, and the seed must be loadable
     *       without the key. So a real deployment turns encryption on over a table that still
     *       contains plaintext.</li>
     *   <li>Rejecting those rows would make every one of them an unreadable 500 - the student's own
     *       data, locked away by the very change meant to protect it.</li>
     * </ul>
     *
     * <p>So a value that is not a well-formed envelope is returned unchanged and a warning naming
     * the field - never the value - is logged once. A value that <em>is</em> an envelope is still
     * passed to {@link #decrypt}, so a tampered ciphertext fails authentication exactly as before;
     * leniency here applies to the legacy-plaintext case only and does not weaken the guarantee for
     * anything this class wrote. When the transitional rows are re-encrypted the warning stops on
     * its own, and a later change can make the strict path unconditional.
     *
     * @param stored the column value; may be {@code null}
     * @return the plaintext, or {@code null} if {@code stored} was {@code null}
     * @throws EncryptionException if the value is an envelope that fails to decrypt
     */
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

    /**
     * Whether a column value looks like an envelope this build can decrypt.
     *
     * <p>Checks the format byte rather than merely "is it Base64", so an ordinary description that
     * happens to be valid Base64 is still treated as plaintext.
     */
    private static boolean isEnvelope(String stored) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return decoded.length >= 2 + IV_LENGTH + 1 && decoded[0] == ENVELOPE_FORMAT;
    }

    /**
     * Encrypts a monetary amount.
     *
     * <p><b>Prepared for OB-013, not yet used.</b> No amount column is encrypted in this build -
     * see the class note on why - so nothing calls this today. It exists, and is tested, so that
     * the deferred work does not also have to invent the amount-encoding contract.
     *
     * <p>The amount is canonicalised to exactly two decimal places before encryption, matching the
     * {@code DECIMAL(15,2)} the columns use. Without that step {@code 12.0} and
     * {@code 12.00} - equal as numbers, different as strings - would encrypt to different
     * envelopes, so the same amount could round-trip to two different spellings depending on which
     * path wrote it, and a test comparing amounts would fail depending on scale.
     *
     * @param amount the amount to protect; may be {@code null}
     * @return the envelope, or {@code null} if {@code amount} was {@code null}
     */
    public String encryptAmount(java.math.BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return encrypt(amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    /**
     * Decrypts a monetary amount.
     *
     * @param stored the envelope written by {@link #encryptAmount}; may be {@code null}
     * @return the amount at scale 2, or {@code null} if {@code stored} was {@code null}
     * @throws EncryptionException if the envelope is unreadable or did not decrypt to a number
     */
    public java.math.BigDecimal decryptAmount(String stored) {
        if (stored == null) {
            return null;
        }
        String plaintext = decrypt(stored);
        try {
            return new java.math.BigDecimal(plaintext).setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException e) {
            // Only reachable if a row was written by something other than encryptAmount, or was
            // tampered with in a way GCM somehow accepted - which it cannot.
            throw new EncryptionException("Stored value did not decrypt to a monetary amount.", e);
        }
    }

    /**
     * Decodes and validates the configured key.
     *
     * <p>Called from the constructor, so an unusable key stops the application at start-up rather
     * than at the first write. Failing early is the point: a service that starts with no key and
     * only discovers it while handling a student's transaction has already accepted the request.
     *
     * @throws IllegalStateException if the key is absent, not Base64, or not exactly 256 bits
     */
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
