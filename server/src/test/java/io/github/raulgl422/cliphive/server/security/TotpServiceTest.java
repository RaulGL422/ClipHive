package io.github.raulgl422.cliphive.server.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class TotpServiceTest {

    private TotpService service;

    @BeforeEach
    void setUp() {
        service = new TotpService();
    }

    // ── Secret generation ─────────────────────────────────────────────────────

    @Test
    void generateSecret_returns_non_null_base32_string() {
        String secret = service.generateSecret();
        assertThat(secret).isNotBlank();
        assertThat(secret).matches("[A-Z2-7]+");
    }

    @Test
    void generateSecret_decodes_to_20_bytes() {
        String secret = service.generateSecret();
        assertThat(service.base32Decode(secret)).hasSize(20);
    }

    // ── Base32 round-trip ─────────────────────────────────────────────────────

    @Test
    void base32_roundtrip_arbitrary_bytes() {
        byte[] original = new byte[]{0x00, 0x01, 0x02, (byte) 0xAB, (byte) 0xFF};
        String encoded = service.base32Encode(original);
        assertThat(encoded).matches("[A-Z2-7]+");
        assertThat(service.base32Decode(encoded)).isEqualTo(original);
    }

    @Test
    void base32_roundtrip_empty() {
        assertThat(service.base32Encode(new byte[0])).isEmpty();
    }

    @Test
    void base32_decode_is_case_insensitive() {
        byte[] bytes = {0x4D, 0x59, 0x53};
        String upper = service.base32Encode(bytes);
        assertThat(service.base32Decode(upper.toLowerCase())).isEqualTo(bytes);
    }

    @Test
    void base32_decode_ignores_padding() {
        byte[] bytes = {0x4D, 0x59, 0x53};
        String padded = service.base32Encode(bytes) + "===";
        assertThat(service.base32Decode(padded)).isEqualTo(bytes);
    }

    // ── TOTP URI ──────────────────────────────────────────────────────────────

    @Test
    void buildTotpUri_has_required_parts() {
        String uri = service.buildTotpUri("MYSECRET", "admin", "ClipHive");
        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=MYSECRET");
        assertThat(uri).contains("issuer=ClipHive");
        assertThat(uri).contains("digits=6");
    }

    // ── Verify ────────────────────────────────────────────────────────────────

    @Test
    void verify_returns_true_for_current_time_step() throws Exception {
        String secret = service.generateSecret();
        String code = computeTotp(service.base32Decode(secret));
        assertThat(service.verify(secret, code)).isTrue();
    }

    @Test
    void verify_returns_false_for_null_code() {
        assertThat(service.verify(service.generateSecret(), null)).isFalse();
    }

    @Test
    void verify_returns_false_for_wrong_length_code() {
        assertThat(service.verify(service.generateSecret(), "12345")).isFalse();
        assertThat(service.verify(service.generateSecret(), "1234567")).isFalse();
    }

    @Test
    void verify_returns_false_for_wrong_code() {
        String secret = service.generateSecret();
        assertThat(service.verify(secret, "000000")).isFalse();
    }

    // ── Helper: compute the current TOTP code from raw secret bytes ───────────

    private static String computeTotp(byte[] secret) throws Exception {
        long counter = Instant.now().getEpochSecond() / 30;
        byte[] msg = new byte[8];
        long c = counter;
        for (int i = 7; i >= 0; i--) { msg[i] = (byte) (c & 0xff); c >>>= 8; }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret, "RAW"));
        byte[] hash = mac.doFinal(msg);
        int offset = hash[hash.length - 1] & 0x0f;
        int code = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format("%06d", code % 1_000_000);
    }
}
