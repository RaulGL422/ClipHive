package io.github.raulgl422.cliphive.server.security;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for data that must be stored encrypted at
 * rest (OAuth tokens, TOTP secret) — docs/DESIGN.md §13.
 *
 * <p>Encrypted payload format: {@code Base64(iv) + ":" + Base64(ciphertext+tag)}.
 * The key is a 32-byte value taken from {@code CLIPHIVE_ENCRYPTION_KEY} (Base64-encoded).
 * When the key is blank (embedded/dev profile), calls throw {@link IllegalStateException}
 * rather than encrypting with a weak key.
 */
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom rng = new SecureRandom();

    public EncryptionService(ClipHiveProperties props) {
        String encodedKey = props.getEncryptionKey();
        if (encodedKey == null || encodedKey.isBlank()) {
            this.keySpec = null;
            return;
        }
        byte[] keyBytes = Base64.getDecoder().decode(encodedKey);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "CLIPHIVE_ENCRYPTION_KEY must be a Base64-encoded 32-byte AES-256 key (got "
                    + keyBytes.length + " bytes)");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encrypted) {
        requireKey();
        String[] parts = encrypted.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid encrypted payload — expected iv:ciphertext");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public boolean isConfigured() {
        return keySpec != null;
    }

    private void requireKey() {
        if (keySpec == null) {
            throw new IllegalStateException(
                    "CLIPHIVE_ENCRYPTION_KEY is not configured — cannot encrypt/decrypt");
        }
    }
}
