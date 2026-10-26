package io.github.raulgl422.cliphive.server.publish.tiktok;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.StatsCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TikTok account metrics (DESIGN.md §12.4, scope {@code user.info.stats}).
 * No per-clip metrics while publishing as drafts: a draft isn't linked to the
 * video that ends up posted from the app.
 */
@Component
public class TikTokStatsCollector implements StatsCollector {

    private final TikTokApi api;

    @Autowired
    public TikTokStatsCollector(ObjectMapper objectMapper) {
        this(TikTokApi.production(objectMapper));
    }

    TikTokStatsCollector(TikTokApi api) {
        this.api = api;
    }

    @Override
    public Platform platform() {
        return Platform.TIKTOK;
    }

    @Override
    public Map<String, Object> postMetrics(String accessToken, String externalId, LocalDate publishedOn) {
        return Map.of();
    }

    @Override
    public Map<String, Object> accountMetrics(String accessToken) throws PublishException {
        JsonNode user = api.get(api.v2("user/info/?fields=follower_count,likes_count,video_count"), accessToken)
                .path("data").path("user");
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (user.has("follower_count")) metrics.put("subscribers", user.path("follower_count").asLong());
        if (user.has("likes_count")) metrics.put("likes", user.path("likes_count").asLong());
        if (user.has("video_count")) metrics.put("videos", user.path("video_count").asLong());
        return metrics;
    }
}
