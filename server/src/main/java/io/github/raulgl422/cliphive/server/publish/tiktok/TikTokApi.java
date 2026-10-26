package io.github.raulgl422.cliphive.server.publish.tiktok;

import io.github.raulgl422.cliphive.server.publish.PublishException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Small HTTP helper for TikTok's Open API v2 (DESIGN.md §11.2), shared by the
 * OAuth provider, the publisher and the stats collector. API responses carry
 * {@code {"data": ..., "error": {"code": "ok" | "<code>", "message"}}}; the
 * OAuth token endpoint answers {@code {"error", "error_description"}} instead.
 */
class TikTokApi {

    /** Errors where waiting is the fix (DESIGN.md §11.3); everything else is final. */
    private static final Set<String> RETRYABLE = Set.of("rate_limit_exceeded", "internal_error");

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    /** {@code https://open.tiktokapis.com} — overridable for tests. */
    final String apiBaseUrl;
    /** {@code https://www.tiktok.com} (authorization page) — overridable for tests. */
    final String webBaseUrl;

    TikTokApi(ObjectMapper objectMapper, String apiBaseUrl, String webBaseUrl) {
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
        this.webBaseUrl = webBaseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    static TikTokApi production(ObjectMapper objectMapper) {
        return new TikTokApi(objectMapper, "https://open.tiktokapis.com", "https://www.tiktok.com");
    }

    /** {@code open.tiktokapis.com/v2/<path>} */
    String v2(String path) {
        return apiBaseUrl + "/v2/" + path;
    }

    /** OAuth token endpoint: form-encoded, errors as {@code error}/{@code error_description}. */
    JsonNode token(Map<String, String> params) throws PublishException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(v2("oauth/token/")))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        HttpResponse<String> response = send(request);
        JsonNode body = parse(response);
        String error = body.path("error").isTextual() ? body.path("error").asText() : "";
        if (response.statusCode() != 200 || !error.isBlank() || body.path("access_token").asText("").isBlank()) {
            String detail = body.path("error_description").asText(error.isBlank() ? "HTTP " + response.statusCode() : error);
            throw new PublishException("TikTok: " + detail, response.statusCode() >= 500);
        }
        return body;
    }

    JsonNode get(String url, String accessToken) throws PublishException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return checked(send(request));
    }

    JsonNode postJson(String url, String accessToken, Object body) throws PublishException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return checked(send(request));
    }

    /** One chunk of a {@code FILE_UPLOAD}; returns the HTTP status (206 more to come, 201 done). */
    int putChunk(String uploadUrl, byte[] chunk, long first, long total) throws PublishException {
        long last = first + chunk.length - 1;
        HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "video/mp4")
                .header("Content-Range", "bytes " + first + "-" + last + "/" + total)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                .build();
        int status = send(request).statusCode();
        if (status == 201 || status == 206) return status;
        // 403: the upload URL expired (1 h); 5xx: TikTok's side — both fixed by starting over later
        throw new PublishException("TikTok rechazó un trozo del vídeo (HTTP " + status + ")",
                status == 403 || status >= 500);
    }

    private HttpResponse<String> send(HttpRequest request) throws PublishException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PublishException("No se pudo contactar con TikTok: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrumpido", true, e);
        }
    }

    private JsonNode parse(HttpResponse<String> response) throws PublishException {
        try {
            return objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new PublishException("Respuesta de TikTok no válida (HTTP " + response.statusCode() + ")",
                    true, e);
        }
    }

    /** Open API envelope: anything but {@code error.code = "ok"} is a failure. */
    private JsonNode checked(HttpResponse<String> response) throws PublishException {
        JsonNode body = parse(response);
        String code = body.path("error").path("code").asText("");
        if (response.statusCode() == 200 && (code.isEmpty() || code.equals("ok"))) return body;

        String message = body.path("error").path("message").asText("");
        String detail = code.isEmpty() ? "HTTP " + response.statusCode() : code;
        if (!message.isBlank()) detail += " (" + message + ")";
        if (code.equals("spam_risk_too_many_pending_share")) {
            // At most 5 drafts waiting in the TikTok inbox per 24 h: wait instead of failing (§11.3)
            throw PublishException.limitReached("TikTok: hay demasiados borradores sin publicar en la app; "
                    + "publica o descarta alguno", Duration.ofHours(1));
        }
        boolean retryable = response.statusCode() >= 500 || response.statusCode() == 429 || RETRYABLE.contains(code);
        throw new PublishException("TikTok: " + detail, retryable);
    }

    static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
