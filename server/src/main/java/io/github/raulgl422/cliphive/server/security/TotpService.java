package io.github.raulgl422.cliphive.server.security;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * TOTP (RFC 6238) implementation for superadmin 2FA — docs/DESIGN.md §12.1.
 * No external dependency: uses HMAC-SHA1 from {@code javax.crypto}.
 *
 * <p>Secrets are stored Base32-encoded (Google Authenticator compatible).
 * Verification allows a ±1 time-step window (30 s each) to tolerate clock skew.
 */
@Service
public class TotpService {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int WINDOW = 1;

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] BASE32_LOOKUP = buildLookup();

    public String generateSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    public boolean verify(String base32Secret, String code) {
        if (code == null || code.length() != CODE_DIGITS) return false;
        byte[] secret = base32Decode(base32Secret);
        long timeStep = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        for (int delta = -WINDOW; delta <= WINDOW; delta++) {
            if (hotp(secret, timeStep + delta).equals(code)) return true;
        }
        return false;
    }

    public String buildTotpUri(String base32Secret, String username, String issuer) {
        return "otpauth://totp/" + issuer + ":" + username
                + "?secret=" + base32Secret + "&issuer=" + issuer + "&digits=" + CODE_DIGITS;
    }

    // ── HOTP (RFC 4226) ───────────────────────────────────────────────────────

    private static String hotp(byte[] secret, long counter) {
        byte[] msg = new byte[8];
        long c = counter;
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (c & 0xff);
            c >>>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "RAW"));
            byte[] hash = mac.doFinal(msg);
            int offset = hash[hash.length - 1] & 0x0f;
            int code = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            code %= 1_000_000;
            return String.format("%06d", code);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    // ── Base32 (RFC 4648, no padding) ─────────────────────────────────────────

    public String base32Encode(byte[] data) {
        if (data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int buffer = data[0];
        int next = 1;
        int bitsLeft = 8;
        while (bitsLeft > 0 || next < data.length) {
            if (bitsLeft < 5) {
                if (next < data.length) {
                    buffer <<= 8;
                    buffer |= (data[next++] & 0xff);
                    bitsLeft += 8;
                } else {
                    int pad = 5 - bitsLeft;
                    buffer <<= pad;
                    bitsLeft += pad;
                }
            }
            int index = 0x1f & (buffer >> (bitsLeft - 5));
            bitsLeft -= 5;
            sb.append(BASE32_ALPHABET.charAt(index));
        }
        return sb.toString();
    }

    public byte[] base32Decode(String base32) {
        String s = base32.toUpperCase().replaceAll("=", "");
        int outputLen = s.length() * 5 / 8;
        byte[] output = new byte[outputLen];
        int buffer = 0;
        int bitsLeft = 0;
        int idx = 0;
        for (char c : s.toCharArray()) {
            int val = BASE32_LOOKUP[c];
            if (val < 0) throw new IllegalArgumentException("Invalid Base32 character: " + c);
            buffer <<= 5;
            buffer |= val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output[idx++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return output;
    }

    private static int[] buildLookup() {
        int[] table = new int[128];
        Arrays.fill(table, -1);
        for (int i = 0; i < BASE32_ALPHABET.length(); i++) {
            table[BASE32_ALPHABET.charAt(i)] = i;
        }
        return table;
    }
}
