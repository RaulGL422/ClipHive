package io.github.raulgl422.cliphive.server.security;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EncryptionServiceTest {

    private static EncryptionService configured() {
        ClipHiveProperties props = new ClipHiveProperties();
        // 32 zero bytes in Base64
        props.setEncryptionKey("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        return new EncryptionService(props);
    }

    private static EncryptionService unconfigured() {
        ClipHiveProperties props = new ClipHiveProperties();
        props.setEncryptionKey("");
        return new EncryptionService(props);
    }

    @Test
    void encrypt_decrypt_roundtrip() {
        EncryptionService svc = configured();
        String plaintext = "my-secret-token";

        String ciphertext = svc.encrypt(plaintext);
        assertThat(ciphertext).contains(":").isNotEqualTo(plaintext);

        assertThat(svc.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void encrypt_produces_different_ciphertexts_for_same_input() {
        EncryptionService svc = configured();
        String a = svc.encrypt("same");
        String b = svc.encrypt("same");
        assertThat(a).isNotEqualTo(b); // random IV per call
    }

    @Test
    void decrypt_rejects_tampered_ciphertext() {
        EncryptionService svc = configured();
        String enc = svc.encrypt("data");
        String tampered = enc.substring(0, enc.length() - 3) + "XXX";
        assertThatThrownBy(() -> svc.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Decryption failed");
    }

    @Test
    void decrypt_rejects_missing_separator() {
        EncryptionService svc = configured();
        assertThatThrownBy(() -> svc.decrypt("nodot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid encrypted payload");
    }

    @Test
    void isConfigured_returns_true_when_key_set() {
        assertThat(configured().isConfigured()).isTrue();
    }

    @Test
    void isConfigured_returns_false_when_key_blank() {
        assertThat(unconfigured().isConfigured()).isFalse();
    }

    @Test
    void encrypt_throws_when_unconfigured() {
        assertThatThrownBy(() -> unconfigured().encrypt("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void decrypt_throws_when_unconfigured() {
        assertThatThrownBy(() -> unconfigured().decrypt("iv:ct"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void constructor_throws_on_wrong_key_length() {
        ClipHiveProperties props = new ClipHiveProperties();
        props.setEncryptionKey("dGVzdC1lbmNyeXB0aW9uLWtleS0zMi1ieXRlcw=="); // 28 bytes
        assertThatThrownBy(() -> new EncryptionService(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte");
    }
}
