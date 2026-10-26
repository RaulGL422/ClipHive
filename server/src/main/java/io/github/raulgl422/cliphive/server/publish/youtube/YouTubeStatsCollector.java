package io.github.raulgl422.cliphive.server.publish.youtube;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.StatsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YouTube metrics (DESIGN.md §12.4): counters from Data API v3 and watch-time
 * / retention from the Analytics API. Analytics lags 1–3 days and needs the
 * {@code yt-analytics.readonly} scope; without it, or before its data
 * arrives, those keys are left out.
 */
@Component
public class YouTubeStatsCollector implements StatsCollector {

    private static final Logger log = LoggerFactory.getLogger(YouTubeStatsCollector.class);

    /** Analytics API metric → key in metrics_json. */
    private static final Map<String, String> ANALYTICS_METRICS = new LinkedHashMap<>();
    static {
        ANALYTICS_METRICS.put("estimatedMinutesWatched", "minutesWatched");
        ANALYTICS_METRICS.put("averageViewDuration", "averageViewDurationSeconds");
        ANALYTICS_METRICS.put("averageViewPercentage", "averageViewPercentage");
        ANALYTICS_METRICS.put("subscribersGained", "subscribersGained");
    }

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final String apiBaseUrl;
    private final String analyticsBaseUrl;

    @Autowired
    public YouTubeStatsCollector(ObjectMapper objectMapper) {
        this(objectMapper, "https://www.googleapis.com/youtube/v3", "https://youtubeanalytics.googleapis.com/v2");
    }

    /** Base URLs overridable for tests against a local HTTP server. */
    YouTubeStatsCollector(ObjectMapper objectMapper, String apiBaseUrl, String analyticsBaseUrl) {
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
        this.analyticsBaseUrl = analyticsBaseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public Platform platform() {
        return Platform.YOUTUBE;
    }

    @Override
    public Map<String, Object> postMetrics(String accessToken, String externalId, LocalDate publishedOn)
            throws PublishException {
        Map<String, Object> metrics = new LinkedHashMap<>();

        JsonNode item = get(apiBaseUrl + "/videos?part=statistics&id=" + enc(externalId), accessToken)
                .path("items").path(0);
        if (item.isMissingNode()) throw new PublishException("El vídeo ya no existe en YouTube", false);
        JsonNode st = item.path("statistics");
        putLong(metrics, "views", st.path("viewCount"));
        putLong(metrics, "likes", st.path("likeCount"));
        putLong(metrics, "comments", st.path("commentCount"));

        try {
            String url = analyticsBaseUrl + "/reports?ids=channel%3D%3DMINE"
                    + "&startDate=" + publishedOn
                    + "&endDate=" + LocalDate.now(ZoneOffset.UTC)
                    + "&metrics=" + String.join(",", ANALYTICS_METRICS.keySet())
                    + "&filters=" + enc("video==" + externalId);
            JsonNode report = get(url, accessToken);
            JsonNode row = report.path("rows").path(0);
            if (!row.isMissingNode()) {
                JsonNode headers = report.path("columnHeaders");
                for (int i = 0; i < headers.size(); i++) {
                    String key = ANALYTICS_METRICS.get(headers.path(i).path("name").asText());
                    if (key != null && !row.path(i).isMissingNode()) metrics.put(key, row.path(i).numberValue());
                }
            }
        } catch (PublishException e) {
            // No analytics scope, or nothing yet: the Data API counters are still worth keeping
            log.debug("YouTube Analytics unavailable for {}: {}", externalId, e.getMessage());
        }
        return metrics;
    }

    @Override
    public Map<String, Object> accountMetrics(String accessToken) throws PublishException {
        JsonNode st = get(apiBaseUrl + "/channels?part=statistics&mine=true", accessToken)
                .path("items").path(0).path("statistics");
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (!st.path("hiddenSubscriberCount").asBoolean(false)) putLong(metrics, "subscribers", st.path("subscriberCount"));
        putLong(metrics, "views", st.path("viewCount"));
        putLong(metrics, "videos", st.path("videoCount"));
        return metrics;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private JsonNode get(String url, String accessToken) throws PublishException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PublishException("No se pudo contactar con YouTube: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrumpido", true, e);
        }
        if (response.statusCode() != 200) {
            throw new PublishException("YouTube HTTP " + response.statusCode() + ": "
                    + YouTubeOAuthProvider.abbreviate(response.body()), response.statusCode() >= 500);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new PublishException("Respuesta de YouTube no válida", true, e);
        }
    }

    /** YouTube sends counters as strings ("1234"). */
    private static void putLong(Map<String, Object> metrics, String key, JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return;
        try {
            metrics.put(key, Long.parseLong(value.asText()));
        } catch (NumberFormatException ignored) {
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
