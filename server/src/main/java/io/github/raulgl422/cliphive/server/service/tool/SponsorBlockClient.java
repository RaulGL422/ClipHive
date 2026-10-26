package io.github.raulgl422.cliphive.server.service.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches SponsorBlock segments for YouTube videos as data (docs/DESIGN.md §4, step 3).
 *
 * <p>Segments are stored alongside the job; they are <em>never applied at download time</em> —
 * the Claude analysis step (step 6) decides what to cut. See docs/DESIGN.md §22.2 (R12)
 * and the SponsorBlock note in §22.3.
 *
 * <p>Returns an empty list for non-YouTube URLs or if no segments are found (HTTP 404).
 * Network failures are logged and treated as empty rather than propagated, so a SponsorBlock
 * outage never blocks the download pipeline.
 */
@Component
public class SponsorBlockClient {

    private static final Logger log = LoggerFactory.getLogger(SponsorBlockClient.class);

    private static final Pattern YT_VIDEO_ID = Pattern.compile(
            "(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})");

    private static final String API_BASE = "https://sponsor.ajay.app/api/skipSegments";
    private static final String CATEGORIES =
            "[\"sponsor\",\"selfpromo\",\"intro\",\"outro\",\"interaction\"]";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SponsorBlockClient(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Returns SponsorBlock segments for {@code sourceUrl}, or an empty list if the URL is
     * not a YouTube video, if no segments exist, or if the API call fails.
     */
    public List<SponsorSegment> fetchSegments(String sourceUrl) {
        String videoId = extractYouTubeId(sourceUrl);
        if (videoId == null) {
            return Collections.emptyList();
        }

        String url = API_BASE
                + "?videoID=" + videoId
                + "&categories=" + URLEncoder.encode(CATEGORIES, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("SponsorBlock API call failed for video {}: {}", videoId, e.getMessage());
            return Collections.emptyList();
        }

        if (response.statusCode() == 404) {
            // No segments submitted for this video — normal case.
            return Collections.emptyList();
        }
        if (response.statusCode() != 200) {
            log.warn("SponsorBlock returned HTTP {} for video {}", response.statusCode(), videoId);
            return Collections.emptyList();
        }

        return parseSegments(response.body(), videoId);
    }

    List<SponsorSegment> parseSegments(String json, String videoId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) return Collections.emptyList();

            List<SponsorSegment> segments = new ArrayList<>();
            for (JsonNode node : root) {
                JsonNode segNode = node.path("segment");
                JsonNode catNode = node.path("category");
                if (segNode.isArray() && segNode.size() == 2 && !catNode.isMissingNode()) {
                    double start = segNode.get(0).asDouble();
                    double end   = segNode.get(1).asDouble();
                    String cat   = catNode.stringValue();
                    segments.add(new SponsorSegment(start, end, cat));
                }
            }
            log.debug("SponsorBlock: {} segments for video {}", segments.size(), videoId);
            return Collections.unmodifiableList(segments);
        } catch (Exception e) {
            log.warn("Failed to parse SponsorBlock response for video {}: {}", videoId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Extracts the 11-character YouTube video ID from a URL, or {@code null} if not a YouTube URL.
     */
    static String extractYouTubeId(String url) {
        if (url == null) return null;
        Matcher m = YT_VIDEO_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }
}
