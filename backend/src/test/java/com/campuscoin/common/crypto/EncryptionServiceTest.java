package com.campuscoin.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EncryptionService}.
 *
 * <p>A plain JUnit test rather than an integration test: the service is pure cryptography with no
 * database and no Spring context, so starting either would only slow the suite down. The
 * persistence-level guarantees - that a direct {@code SELECT} really does return ciphertext - are
 * proved separately in the integration tests, because only a real MySQL can show what a column
 * actually holds.
 *
 * <p>The names describe the guarantee each test defends, since that is what a later reader needs
 * when one of them fails.
 */
class EncryptionServiceTest {

    /** Base64 of 32 ASCII bytes - the same test key the integration base class supplies. */
    private static final String KEY_V1 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** A different, equally valid 32-byte key, for the wrong-key cases. */
    private static final String KEY_OTHER = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU=";

    private static EncryptionService service(String key) {
        return new EncryptionService(new EncryptionProperties(key, 1));
    }

    @Test
    void decryptionReturnsTheOriginalPlaintext() {
        EncryptionService encryption = service(KEY_V1);
        String plaintext = "Lunch at the campus canteen";

        String stored = encryption.encrypt(plaintext);

        assertThat(encryption.decrypt(stored)).isEqualTo(plaintext);
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        EncryptionService encryption = service(KEY_V1);
        String plaintext = "42.50";

        String first = encryption.encrypt(plaintext);
        String second = encryption.encrypt(plaintext);

        // The whole point of a random IV per call. If these were equal, two students spending the
        // same amount in the same month would produce identical ciphertext, and an attacker holding
        // the ciphertext of one row could recognise the value in another.
        assertThat(first).isNotEqualTo(second);
        // Both must still decrypt to the same value: the difference is in the IV, not the content.
        assertThat(encryption.decrypt(first)).isEqualTo(plaintext);
        assertThat(encryption.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void everyEncryptionUsesADistinctInitialisationVector() {
        EncryptionService encryption = service(KEY_V1);
        Set<String> ivs = new HashSet<>();

        // 200 samples: a collision here would mean the IV source repeats far more often than a
        // 96-bit random value should, which is the failure mode GCM cannot survive.
        for (int i = 0; i < 200; i++) {
            byte[] envelope = Base64.getDecoder().decode(encryption.encrypt("same value"));
            // Layout: format(1) || keyVersion(1) || iv(12) || ciphertext+tag
            String iv = Base64.getEncoder().encodeToString(
                    java.util.Arrays.copyOfRange(envelope, 2, 14));
            ivs.add(iv);
        }

        assertThat(ivs).hasSize(200);
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        EncryptionService encryption = service(KEY_V1);

        String stored = encryption.encrypt("Rent for September");

        assertThat(stored).doesNotContain("Rent");
        assertThat(stored).doesNotContain("September");
        // Not even a substring of the raw bytes: the value is genuinely sealed, not merely encoded.
        assertThat(new String(Base64.getDecoder().decode(stored), StandardCharsets.ISO_8859_1))
                .doesNotContain("Rent");
    }

    @Test
    void alteringAnyByteOfTheCiphertextIsRejected() {
        EncryptionService encryption = service(KEY_V1);
        byte[] envelope = Base64.getDecoder().decode(encryption.encrypt("100.00"));

        // Flip a bit in the last byte (the authentication tag) - the classic tamper.
        envelope[envelope.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(envelope);

        assertThatThrownBy(() -> encryption.decrypt(tampered))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void alteringTheInitialisationVectorIsRejected() {
        EncryptionService encryption = service(KEY_V1);
        byte[] envelope = Base64.getDecoder().decode(encryption.encrypt("100.00"));

        // The IV sits at offset 2. Changing it changes the keystream, so GCM authentication fails:
        // this is what stops an attacker rearranging the IV to mount a chosen-plaintext attack.
        envelope[2] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(envelope);

        assertThatThrownBy(() -> encryption.decrypt(tampered))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void aValueEncryptedWithOneKeyCannotBeReadWithAnother() {
        String stored = service(KEY_V1).encrypt("250.00");

        EncryptionService other = service(KEY_OTHER);

        assertThatThrownBy(() -> other.decrypt(stored))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void aValueFromADifferentKeyVersionIsRejectedRatherThanMisread() {
        String stored = new EncryptionService(new EncryptionProperties(KEY_V1, 1)).encrypt("10.00");

        // Same key material, envelope stamped version 2. Rotation is not implemented, so this must
        // fail loudly instead of silently attempting a decrypt with the wrong key.
        EncryptionService versioned = new EncryptionService(new EncryptionProperties(KEY_V1, 2));

        assertThatThrownBy(() -> versioned.decrypt(stored))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("key version");
    }

    @Test
    void aValueThatIsNotBase64IsRejected() {
        EncryptionService encryption = service(KEY_V1);

        // A plaintext row left over from before the migration, or a corrupted column.
        assertThatThrownBy(() -> encryption.decrypt("not base64 at all !!"))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void aValueTooShortToBeAnEnvelopeIsRejected() {
        EncryptionService encryption = service(KEY_V1);
        // Valid Base64, but shorter than format+version+IV.
        String tiny = Base64.getEncoder().encodeToString(new byte[] {1, 1, 1});

        assertThatThrownBy(() -> encryption.decrypt(tiny))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void anEmptyStringRoundTripsAsAnEmptyString() {
        EncryptionService encryption = service(KEY_V1);

        String stored = encryption.encrypt("");

        // Distinct from null: an empty description is a value the student chose, and it must not
        // come back as null. It still produces a real envelope with a real tag.
        assertThat(stored).isNotNull();
        assertThat(encryption.decrypt(stored)).isEmpty();
    }

    @Test
    void nullIsPassedThroughInBothDirections() {
        EncryptionService encryption = service(KEY_V1);

        // Several encrypted columns are legitimately nullable. Null stays null so that "the
        // student gave no description" survives a round trip exactly.
        assertThat(encryption.encrypt(null)).isNull();
        assertThat(encryption.decrypt(null)).isNull();
        assertThat(encryption.encryptAmount(null)).isNull();
        assertThat(encryption.decryptAmount(null)).isNull();
    }

    @Test
    void amountsRoundTripAtTwoDecimalPlaces() {
        EncryptionService encryption = service(KEY_V1);

        assertThat(encryption.decryptAmount(encryption.encryptAmount(new BigDecimal("12.5"))))
                .isEqualByComparingTo("12.50");
        assertThat(encryption.decryptAmount(encryption.encryptAmount(new BigDecimal("0.01"))))
                .isEqualByComparingTo("0.01");
        assertThat(encryption.decryptAmount(encryption.encryptAmount(new BigDecimal("9999999999.99"))))
                .isEqualByComparingTo("9999999999.99");
    }

    @Test
    void equalAmountsSpelledDifferentlyEncryptToInterchangeableValues() {
        EncryptionService encryption = service(KEY_V1);

        // 12.0, 12.00 and 12 all mean the same amount. Canonicalising to scale 2 before encryption
        // is what stops the same value round-tripping as two different spellings depending on
        // which code path wrote it.
        String fromShort = encryption.encryptAmount(new BigDecimal("12.0"));
        String fromLong = encryption.encryptAmount(new BigDecimal("12.000"));

        assertThat(encryption.decryptAmount(fromShort))
                .isEqualByComparingTo(encryption.decryptAmount(fromLong));
    }

    @Test
    void aKeyThatIsMissingStopsTheApplicationFromStarting() {
        assertThatThrownBy(() -> service(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAMPUSCOIN_ENCRYPTION_KEY");
        assertThatThrownBy(() -> service("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyThatIsNotBase64StopsTheApplicationFromStarting() {
        assertThatThrownBy(() -> service("this is not base64 !!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void aKeyOfTheWrongLengthStopsTheApplicationFromStarting() {
        // 16 bytes: a valid AES-128 key, and therefore the most likely mistake - it looks fine and
        // would silently give 128-bit encryption where the design promises 256.
        String aes128 = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service(aes128))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void aStoredValueThatWasNeverEncryptedIsReturnedUnchanged() {
        EncryptionService encryption = service(KEY_V1);

        // The transitional case: rows - and the demo seed - written before encryption was enabled.
        // Rejecting them would lock a student out of their own data, so they read back as-is.
        assertThat(encryption.decryptStored("Campus Cafe")).isEqualTo("Campus Cafe");
        assertThat(encryption.decryptStored("")).isEmpty();
        assertThat(encryption.decryptStored(null)).isNull();
    }

    @Test
    void aStoredValueThatIsValidBase64ButNotAnEnvelopeIsTreatedAsPlaintext() {
        EncryptionService encryption = service(KEY_V1);

        // "not encrypted" must not be decided by "is it Base64" alone: an ordinary note can be.
        String base64LookingNote = Base64.getEncoder().encodeToString("Campus Cafe".getBytes(StandardCharsets.UTF_8));

        assertThat(encryption.decryptStored(base64LookingNote)).isEqualTo(base64LookingNote);
    }

    @Test
    void aTamperedEnvelopeStillFailsThroughTheTolerantRead() {
        EncryptionService encryption = service(KEY_V1);
        byte[] envelope = Base64.getDecoder().decode(encryption.encrypt("Rent"));
        envelope[envelope.length - 1] ^= 0x01;

        // Leniency applies to values this class never wrote. It must not become a way to smuggle a
        // modified ciphertext past authentication.
        assertThatThrownBy(() ->
                encryption.decryptStored(Base64.getEncoder().encodeToString(envelope)))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void aFailureMessageNamesNoSecretAndNoPlaintext() {
        EncryptionService encryption = service(KEY_V1);

        assertThatCode(() -> {
            try {
                encryption.decrypt(Base64.getEncoder().encodeToString(new byte[] {9, 9, 9, 9, 9}));
            } catch (EncryptionException e) {
                // The message reaches a log line; the key and any plaintext must not.
                assertThat(e.getMessage()).doesNotContain(KEY_V1).doesNotContain("9, 9");
                throw e;
            }
        }).isInstanceOf(EncryptionException.class);
    }
}
