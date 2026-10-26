package io.github.raulgl422.cliphive.server.publish.youtube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** YouTube Data + Analytics metrics against a local stand-in for both APIs. */
class YouTubeStatsCollectorTest {

    private HttpServer server;
    private YouTubeStatsCollector collector;
    private volatile int analyticsStatus = 200;
    private volatile String analyticsBody = "";
    private final AtomicReference<String> analyticsQuery = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/videos", ex -> respond(ex, 200,
                "{\"items\":[{\"statistics\":{\"viewCount\":\"1520\",\"likeCount\":\"88\",\"commentCount\":\"7\"}}]}"));
        server.createContext("/api/channels", ex -> respond(ex, 200,
                "{\"items\":[{\"statistics\":{\"subscriberCount\":\"340\",\"viewCount\":\"98000\",\"videoCount\":\"41\",\"hiddenSubscriberCount\":false}}]}"));
        server.createContext("/analytics/reports", ex -> {
            analyticsQuery.set(URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            respond(ex, analyticsStatus, analyticsBody);
        });
        server.start();
        collector = new YouTubeStatsCollector(JsonMapper.builder().build(), base + "/api", base + "/analytics");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void video_metrics_combine_data_api_counters_and_analytics() throws Exception {
        analyticsBody = """
                {"columnHeaders":[{"name":"estimatedMinutesWatched"},{"name":"averageViewDuration"},
                                  {"name":"averageViewPercentage"},{"name":"subscribersGained"}],
                 "rows":[[310, 24, 61.5, 3]]}""";

        Map<String, Object> m = collector.postMetrics("tok", "vid1", LocalDate.of(2026, 10, 1));

        assertThat(m).containsEntry("views", 1520L).containsEntry("likes", 88L).containsEntry("comments", 7L);
        assertThat(((Number) m.get("averageViewPercentage")).doubleValue()).isEqualTo(61.5);
        assertThat(((Number) m.get("averageViewDurationSeconds")).intValue()).isEqualTo(24);
        assertThat(((Number) m.get("minutesWatched")).intValue()).isEqualTo(310);
        assertThat(((Number) m.get("subscribersGained")).intValue()).isEqualTo(3);
        assertThat(analyticsQuery.get()).contains("ids=channel==MINE", "filters=video==vid1", "startDate=2026-10-01");
    }

    @Test
    void without_the_analytics_scope_the_counters_are_still_collected() throws Exception {
        analyticsStatus = 403;
        analyticsBody = "{\"error\":{\"message\":\"Insufficient Permission\"}}";

        Map<String, Object> m = collector.postMetrics("tok", "vid1", LocalDate.now());

        assertThat(m).containsEntry("views", 1520L).doesNotContainKey("averageViewPercentage");
    }

    @Test
    void analytics_not_ready_yet_leaves_those_keys_out() throws Exception {
        analyticsBody = "{\"columnHeaders\":[{\"name\":\"estimatedMinutesWatched\"}],\"rows\":[]}";

        Map<String, Object> m = collector.postMetrics("tok", "vid1", LocalDate.now());

        assertThat(m).containsOnlyKeys("views", "likes", "comments");
    }

    @Test
    void channel_metrics() throws Exception {
        assertThat(collector.accountMetrics("tok"))
                .containsEntry("subscribers", 340L).containsEntry("views", 98000L).containsEntry("videos", 41L);
    }
}
