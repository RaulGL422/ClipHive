package io.github.raulgl422.cliphive.server.publish.youtube;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.publish.OAuthProvider;
import io.github.raulgl422.cliphive.server.publish.OAuthTokens;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google OAuth for YouTube (DESIGN.md §11.5): {@code youtube.upload} to
 * publish, {@code youtube.readonly} for the channel and its counters and
 * {@code yt-analytics.readonly} for watch time and retention (§12.4).
 * {@code access_type=offline} + {@code prompt=consent} so Google always
 * returns a refresh token.
 */
@Component
public class YouTubeOAuthProvider implements OAuthProvider {

    static final String SCOPES = "https://www.googleapis.com/auth/youtube.upload "
            + "https://www.googleapis.com/auth/youtube "
            + "https://www.googleapis.com/auth/youtube.readonly "
            + "https://www.googleapis.com/auth/yt-analytics.readonly";

    private final ClipHiveProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final String authBaseUrl;
    private final String tokenUrl;
    private final String apiBaseUrl;

    @Autowired
    public YouTubeOAuthProvider(ClipHiveProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token", "https://www.googleapis.com/youtube/v3");
    }

    /** Base URLs overridable for tests against a local HTTP server. */
    YouTubeOAuthProvider(ClipHiveProperties props, ObjectMapper objectMapper,
                         String authBaseUrl, String tokenUrl, String apiBaseUrl) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.authBaseUrl = authBaseUrl;
        this.tokenUrl = tokenUrl;
        this.apiBaseUrl = apiBaseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public Platform platform() {
        return Platform.YOUTUBE;
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(props.getGoogleClientId()) && StringUtils.hasText(props.getGoogleClientSecret());
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", props.getGoogleClientId());
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", SCOPES);
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        params.put("state", state);
        return authBaseUrl + "?" + form(params);
    }

    @Override
    public OAuthTokens exchangeCode(String code, String redirectUri) throws PublishException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("client_id", props.getGoogleClientId());
        params.put("client_secret", props.getGoogleClientSecret());
        params.put("redirect_uri", redirectUri);
        params.put("grant_type", "authorization_code");
        JsonNode token = postToken(params);

        String accessToken = token.path("access_token").asText();
        String refreshToken = token.path("refresh_token").asText(null);
        Instant expiresAt = Instant.now().plusSeconds(token.path("expires_in").asLong(3600));

        // Which channel did the user pick?
        JsonNode channels = getJson(apiBaseUrl + "/channels?part=snippet&mine=true", accessToken);
        JsonNode channel = channels.path("items").path(0);
        if (channel.isMissingNode() || channel.path("id").asText("").isBlank()) {
            throw new PublishException("La cuenta de Google no tiene canal de YouTube", false);
        }
        return new OAuthTokens(accessToken, refreshToken, expiresAt,
                channel.path("id").asText(), channel.path("snippet").path("title").asText(null));
    }

    @Override
    public OAuthTokens refresh(String refreshToken) throws PublishException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", props.getGoogleClientId());
        params.put("client_secret", props.getGoogleClientSecret());
        params.put("refresh_token", refreshToken);
        params.put("grant_type", "refresh_token");
        JsonNode token = postToken(params);
        return new OAuthTokens(token.path("access_token").asText(),
                token.path("refresh_token").asText(null),
                Instant.now().plusSeconds(token.path("expires_in").asLong(3600)),
                null, null);
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private JsonNode postToken(Map<String, String> params) throws PublishException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            // invalid_grant = the refresh token was revoked or expired: reconnect by hand
            boolean retryable = response.statusCode() >= 500;
            throw new PublishException("Google OAuth HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body()), retryable);
        }
        return readJson(response.body());
    }

    private JsonNode getJson(String url, String accessToken) throws PublishException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new PublishException("YouTube API HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body()), response.statusCode() >= 500);
        }
        return readJson(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws PublishException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PublishException("No se pudo contactar con Google: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrumpido", true, e);
        }
    }

    private JsonNode readJson(String body) throws PublishException {
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new PublishException("Respuesta de Google no válida", true, e);
        }
    }

    static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
