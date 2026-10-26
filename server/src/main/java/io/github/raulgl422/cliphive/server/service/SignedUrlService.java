package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates and validates short-lived, HMAC-SHA256-signed URLs for two
 * surfaces (docs/DESIGN.md §7, §13):
 *
 * <ul>
 *   <li><b>Worker file URLs</b> — tied to a specific {@code taskId + attempt};
 *       the worker uses them to download inputs and upload results over HTTP.
 *       The {@code attempt} acts as the fencing token: a reconnected worker
 *       with an old {@code attempt} can't upload stale results.
 *   <li><b>Media clip URLs</b> — a short-lived token (reusable until it expires) served at
 *       {@code GET /media/{token}}; lets Instagram (or any public HTTP client)
 *       fetch a rendered clip without going through Cloudflare Access.
 * </ul>
 *
 * <p>Tokens are URL-safe Base64 payloads of the form
 * {@code <payload_hex>.<signature_hex>} where:
 * <ul>
 *   <li>{@code payload} encodes the type, IDs, expiry and optional path.
 *   <li>{@code signature} is HMAC-SHA256({@code payload}, {@code urlSigningKey}).
 * </ul>
 *
 * <p>The application fails to start if {@code CLIPHIVE_URL_SIGNING_KEY} is
 * blank — see the constructor guard below.
 */
@Component
public class SignedUrlService {

    private static final String HMAC_ALG = "HmacSHA256";

    /** Validity window for media clip tokens. 1 h is enough for Instagram to process. */
    static final long MEDIA_TOKEN_TTL_SECONDS = 3600;

    /** Validity window for worker file URLs. 24 h covers slow or retried tasks. */
    static final long WORKER_URL_TTL_SECONDS = 86_400;

    private final byte[] signingKey;
    private final String mediaPublicUrl;

    public SignedUrlService(ClipHiveProperties props) {
        String key = props.getUrlSigningKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "CLIPHIVE_URL_SIGNING_KEY must be set — it signs worker file URLs and media tokens");
        }
        this.signingKey = key.getBytes(StandardCharsets.UTF_8);
        this.mediaPublicUrl = props.getMediaPublicUrl();
    }

    // ── Media clip tokens ───────────────────────────────────────────────────

    /**
     * Generates a signed token for a rendered clip served at
     * {@code GET /media/{token}} (docs/DESIGN.md §13).
     *
     * @param clipId  the clip's UUID
     * @return absolute URL the caller can give to Instagram
     */
    public String generateMediaUrl(UUID clipId) {
        long expiresAt = Instant.now().getEpochSecond() + MEDIA_TOKEN_TTL_SECONDS;
        String payload = "media:" + clipId + ":" + expiresAt;
        String token = buildToken(payload);
        return mediaPublicUrl + "/media/" + token;
    }

    /**
     * Validates and unpacks a media token from {@code GET /media/{token}}.
     *
     * @return the clip id if valid, or {@code null} if the token is expired,
     *         tampered or malformed
     */
    public UUID validateMediaToken(String token) {
        String payload = verifyToken(token);
        if (payload == null) return null;
        String[] parts = payload.split(":");
        if (parts.length != 3 || !"media".equals(parts[0])) return null;
        try {
            long expiresAt = Long.parseLong(parts[2]);
            if (Instant.now().getEpochSecond() > expiresAt) return null;
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Worker file URLs ────────────────────────────────────────────────────

    /**
     * Generates a signed URL a worker can use to download or upload a file
     * for the given task attempt (docs/DESIGN.md §7).
     *
     * @param baseUrl  the worker-connector base URL, e.g. {@code http://100.x.x.x:8082}
     * @param taskId   task being processed
     * @param attempt  fencing token — stale workers with old attempts can't use the URL
     * @param path     path under the base URL, e.g. {@code /tasks/{id}/{attempt}/source}
     */
    public String generateWorkerUrl(String baseUrl, UUID taskId, int attempt, String path) {
        long expiresAt = Instant.now().getEpochSecond() + WORKER_URL_TTL_SECONDS;
        String payload = "worker:" + taskId + ":" + attempt + ":" + path + ":" + expiresAt;
        String token = buildToken(payload);
        return baseUrl + path + "?token=" + token;
    }

    /**
     * Validates a worker file URL token from a query parameter.
     *
     * @return {@code true} if the token is valid, not expired, and matches
     *         the expected {@code taskId}, {@code attempt} and {@code path}
     */
    public boolean validateWorkerToken(String token, UUID taskId, int attempt, String path) {
        String payload = verifyToken(token);
        if (payload == null) return false;
        String[] parts = payload.split(":", 6);
        if (parts.length != 5 || !"worker".equals(parts[0])) return false;
        try {
            if (!taskId.toString().equals(parts[1])) return false;
            if (Integer.parseInt(parts[2]) != attempt) return false;
            if (!path.equals(parts[3])) return false;
            long expiresAt = Long.parseLong(parts[4]);
            return Instant.now().getEpochSecond() <= expiresAt;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /** Builds a {@code payload_hex.sig_hex} token for {@code payload}. */
    private String buildToken(String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String payloadHex = HexFormat.of().formatHex(payloadBytes);
        String sigHex = HexFormat.of().formatHex(hmac(payloadBytes));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payloadHex + "." + sigHex).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verifies the token and returns the raw payload string, or {@code null}
     * on any failure (tampered, malformed, wrong key).
     */
    private String verifyToken(String token) {
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int dot = decoded.indexOf('.');
            if (dot < 0) return null;
            byte[] payloadBytes = HexFormat.of().parseHex(decoded.substring(0, dot));
            byte[] expectedSig = HexFormat.of().parseHex(decoded.substring(dot + 1));
            byte[] actualSig = hmac(payloadBytes);
            if (!MessageDigest.isEqual(expectedSig, actualSig)) return null;
            return new String(payloadBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALG));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }
}
