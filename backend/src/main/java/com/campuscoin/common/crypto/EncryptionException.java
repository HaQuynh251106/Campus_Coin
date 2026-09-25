package com.campuscoin.common.crypto;

/**
 * Raised when ciphertext cannot be decrypted.
 *
 * <p>This is deliberately a plain {@link RuntimeException} rather than an {@code ApiException}.
 * Every caller that can reach it is server-side - a client never supplies ciphertext, only
 * plaintext that the application then encrypts - so a failure here means the stored data is
 * corrupt, was written with a different key, or was tampered with. That is a fault in the
 * application or its data, not a bad request, and it must surface as {@code 500 INTERNAL_ERROR}
 * through the generic handler rather than as a client-visible 4xx.
 *
 * <p>The message never contains the key material, the plaintext, the ciphertext or the IV. A
 * GCM authentication failure cannot say <em>which</em> byte range was altered without becoming an
 * oracle, so it does not try.
 */
public class EncryptionException extends RuntimeException {

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
