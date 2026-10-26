package io.github.raulgl422.cliphive.server.publish.instagram;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.StatsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instagram Reels insights (DESIGN.md §12.4): views, reach, interactions and
 * watch time, which Meta reports in milliseconds. Insights can lag up to 48 h;
 * if they fail, the like/comment counters from the media itself are kept.
 */
@Component
public class InstagramStatsCollector implements StatsCollector {

    private static final Logger log = LoggerFactory.getLogger(InstagramStatsCollector.class);

    static final String METRICS = "views,reach,likes,comments,shares,saved,"
            + "ig_reels_avg_watch_time,ig_reels_video_view_total_time";

    private final InstagramApi api;

    @Autowired
    public InstagramStatsCollector(ObjectMapper objectMapper) {
        this(InstagramApi.production(objectMapper));
    }

    InstagramStatsCollector(InstagramApi api) {
        this.api = api;
    }

    @Override
    public Platform platform() {
        return Platform.INSTAGRAM;
    }

    @Override
    public Map<String, Object> postMetrics(String accessToken, String externalId, LocalDate publishedOn)
            throws PublishException {
        Map<String, Object> metrics = new LinkedHashMap<>();
        try {
            JsonNode data = api.get(api.graph(externalId + "/insights"), Map.of(
                    "metric", METRICS, "access_token", accessToken)).path("data");
            for (JsonNode m : data) {
                JsonNode value = m.path("values").path(0).path("value");
                if (!value.isNumber()) continue;
                switch (m.path("name").asText()) {
                    case "views", "reach", "likes", "comments", "shares", "saved" ->
                            metrics.put(m.path("name").asText(), value.asLong());
                    case "ig_reels_avg_watch_time" ->
                            metrics.put("averageViewDurationSeconds", value.asDouble() / 1000.0);
                    case "ig_reels_video_view_total_time" ->
                            metrics.put("minutesWatched", value.asDouble() / 60_000.0);
                    default -> { }
                }
            }
        } catch (PublishException e) {
            log.debug("Instagram insights unavailable for {}: {}", externalId, e.getMessage());
            JsonNode media = api.get(api.graph(externalId), Map.of(
                    "fields", "like_count,comments_count", "access_token", accessToken));
            if (media.has("like_count")) metrics.put("likes", media.path("like_count").asLong());
            if (media.has("comments_count")) metrics.put("comments", media.path("comments_count").asLong());
        }
        return metrics;
    }

    @Override
    public Map<String, Object> accountMetrics(String accessToken) throws PublishException {
        JsonNode me = api.get(api.graph("me"), Map.of(
                "fields", "followers_count,media_count", "access_token", accessToken));
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (me.has("followers_count")) metrics.put("subscribers", me.path("followers_count").asLong());
        if (me.has("media_count")) metrics.put("videos", me.path("media_count").asLong());
        return metrics;
    }
}
