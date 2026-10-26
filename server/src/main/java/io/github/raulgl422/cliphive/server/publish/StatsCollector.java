package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.server.model.enums.Platform;

import java.time.LocalDate;
import java.util.Map;

/**
 * Reads a platform's metrics for the statistics page (DESIGN.md §12.4). One
 * implementation per platform; keys follow the {@code post_stats} /
 * {@code account_stats} schema in §14.2 and a metric the platform doesn't
 * expose is simply left out.
 */
public interface StatsCollector {

    Platform platform();

    /** Metrics of one published video. {@code publishedOn} bounds the analytics date range. */
    Map<String, Object> postMetrics(String accessToken, String externalId, LocalDate publishedOn)
            throws PublishException;

    /** Metrics of the connected account itself. */
    Map<String, Object> accountMetrics(String accessToken) throws PublishException;
}
