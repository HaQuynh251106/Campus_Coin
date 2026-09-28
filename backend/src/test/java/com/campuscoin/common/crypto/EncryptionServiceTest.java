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

class EncryptionServiceTest {

    private static final String KEY_V1 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

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

        assertThat(first).isNotEqualTo(second);

        assertThat(encryption.decrypt(first)).isEqualTo(plaintext);
        assertThat(encryption.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void everyEncryptionUsesADistinctInitialisationVector() {
        EncryptionService encryption = service(KEY_V1);
        Set<String> ivs = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            byte[] envelope = Base64.getDecoder().decode(encryption.encrypt("same value"));

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

        assertThat(new String(Base64.getDecoder().decode(stored), StandardCharsets.ISO_8859_1))
                .doesNotContain("Rent");
    }

    @Test
    void alteringAnyByteOfTheCiphertextIsRejected() {
        EncryptionService encryption = service(KEY_V1);
        byte[] envelope = Base64.getDecoder().decode(encryption.encrypt("100.00"));

        envelope[envelope.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(envelope);

        assertThatThrownBy(() -> encryption.decrypt(tampered))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void alteringTheInitialisationVectorIsRejected() {
        EncryptionService encryption = service(KEY_V1);
        byte[] envelope = Base64.getDecoder().decode(encryption.encrypt("100.00"));

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

        EncryptionService versioned = new EncryptionService(new EncryptionProperties(KEY_V1, 2));

        assertThatThrownBy(() -> versioned.decrypt(stored))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("key version");
    }

    @Test
    void aValueThatIsNotBase64IsRejected() {
        EncryptionService encryption = service(KEY_V1);

        assertThatThrownBy(() -> encryption.decrypt("not base64 at all !!"))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void aValueTooShortToBeAnEnvelopeIsRejected() {
        EncryptionService encryption = service(KEY_V1);

        String tiny = Base64.getEncoder().encodeToString(new byte[] {1, 1, 1});

        assertThatThrownBy(() -> encryption.decrypt(tiny))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void anEmptyStringRoundTripsAsAnEmptyString() {
        EncryptionService encryption = service(KEY_V1);

        String stored = encryption.encrypt("");

        assertThat(stored).isNotNull();
        assertThat(encryption.decrypt(stored)).isEmpty();
    }

    @Test
    void nullIsPassedThroughInBothDirections() {
        EncryptionService encryption = service(KEY_V1);

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

        String aes128 = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service(aes128))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void aStoredValueThatWasNeverEncryptedIsReturnedUnchanged() {
        EncryptionService encryption = service(KEY_V1);

        assertThat(encryption.decryptStored("Campus Cafe")).isEqualTo("Campus Cafe");
        assertThat(encryption.decryptStored("")).isEmpty();
        assertThat(encryption.decryptStored(null)).isNull();
    }

    @Test
    void aStoredValueThatIsValidBase64ButNotAnEnvelopeIsTreatedAsPlaintext() {
        EncryptionService encryption = service(KEY_V1);

        String base64LookingNote = Base64.getEncoder().encodeToString("Campus Cafe".getBytes(StandardCharsets.UTF_8));

        assertThat(encryption.decryptStored(base64LookingNote)).isEqualTo(base64LookingNote);
    }

    @Test
    void aTamperedEnvelopeStillFailsThroughTheTolerantRead() {
        EncryptionService encryption = service(KEY_V1);
        byte[] envelope = Base64.getDecoder().decode(encryption.encrypt("Rent"));
        envelope[envelope.length - 1] ^= 0x01;

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

                assertThat(e.getMessage()).doesNotContain(KEY_V1).doesNotContain("9, 9");
                throw e;
            }
        }).isInstanceOf(EncryptionException.class);
    }
}
