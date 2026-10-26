package io.github.raulgl422.cliphive.server.publish.instagram;

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
import java.util.stream.Collectors;

/**
 * Small HTTP helper for the Instagram API with Instagram Login (DESIGN.md
 * §11.2) shared by the OAuth provider, the publisher and the stats collector.
 * Graph errors come back as {@code {"error": {"message", "code", ...}}}.
 */
class InstagramApi {

    /** Version used in Meta's current examples. */
    static final String VERSION = "v25.0";

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    /** {@code https://graph.instagram.com} — overridable for tests. */
    final String graphBaseUrl;
    /** {@code https://api.instagram.com} (short-lived token exchange) — overridable for tests. */
    final String apiBaseUrl;
    /** {@code https://www.instagram.com} (authorization page) — overridable for tests. */
    final String webBaseUrl;

    InstagramApi(ObjectMapper objectMapper, String graphBaseUrl, String apiBaseUrl, String webBaseUrl) {
        this.objectMapper = objectMapper;
        this.graphBaseUrl = graphBaseUrl;
        this.apiBaseUrl = apiBaseUrl;
        this.webBaseUrl = webBaseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    static InstagramApi production(ObjectMapper objectMapper) {
        return new InstagramApi(objectMapper, "https://graph.instagram.com",
                "https://api.instagram.com", "https://www.instagram.com");
    }

    /** {@code graph.instagram.com/<version>/<path>} */
    String graph(String path) {
        return graphBaseUrl + "/" + VERSION + "/" + path;
    }

    JsonNode get(String url, Map<String, String> params) throws PublishException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "?" + form(params)))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return send(request);
    }

    JsonNode postForm(String url, Map<String, String> params) throws PublishException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) throws PublishException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PublishException("No se pudo contactar con Instagram: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrumpido", true, e);
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new PublishException("Respuesta de Instagram no válida (HTTP " + response.statusCode() + ")", true, e);
        }
        if (response.statusCode() != 200) {
            JsonNode err = body.path("error");
            String message = err.path("message").asText(body.path("error_message").asText("HTTP " + response.statusCode()));
            // 5xx, rate limits (4, 17, 32, 613) and "temporarily unavailable" (2) are worth retrying
            int code = err.path("code").asInt(0);
            boolean retryable = response.statusCode() >= 500 || response.statusCode() == 429
                    || code == 2 || code == 4 || code == 17 || code == 32 || code == 613
                    || err.path("is_transient").asBoolean(false);
            throw new PublishException("Instagram: " + message, retryable);
        }
        return body;
    }

    static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
