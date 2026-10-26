package io.github.raulgl422.cliphive.server.publish.instagram;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.publish.OAuthTokens;
import io.github.raulgl422.cliphive.server.publish.PostMetadata;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.PublishItem;
import io.github.raulgl422.cliphive.server.publish.UploadResult;
import io.github.raulgl422.cliphive.server.service.SignedUrlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OAuth, Reels publishing and insights against a local stand-in for the
 * Instagram API with Instagram Login (graph.instagram.com / api.instagram.com).
 */
class InstagramApiTest {

    private HttpServer server;
    private InstagramApi api;
    private ClipHiveProperties props;

    /** Request bodies/queries the fake received, by path. */
    private final Map<String, String> received = new ConcurrentHashMap<>();
    private final AtomicInteger statusPolls = new AtomicInteger();
    private volatile long quotaUsage = 3;
    private volatile String containerFinalStatus = "FINISHED";
    private volatile boolean insightsFail = false;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
        api = new InstagramApi(JsonMapper.builder().build(), base, base, base);

        props = new ClipHiveProperties();
        props.setMetaAppId("app-id");
        props.setMetaAppSecret("app-secret");
        props.setUrlSigningKey("test-signing-key");
        props.setMediaPublicUrl("https://cliphive-media.example.com");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String query = ex.getRequestURI().getRawQuery();
        received.put(path, URLDecoder.decode(body.isEmpty() && query != null ? query : body, StandardCharsets.UTF_8));
        String v = "/" + InstagramApi.VERSION;
        String json = switch (path) {
            case "/oauth/access_token" -> "{\"data\":[{\"access_token\":\"short\",\"user_id\":\"17\",\"permissions\":\"x\"}]}";
            case "/access_token" -> "{\"access_token\":\"long\",\"token_type\":\"bearer\",\"expires_in\":5184000}";
            case "/refresh_access_token" -> "{\"access_token\":\"long2\",\"expires_in\":5184000}";
            default -> null;
        };
        if (json == null) {
            if (path.equals(v + "/me")) json = "{\"user_id\":\"1784\",\"username\":\"cliphive.demo\",\"followers_count\":950,\"media_count\":12}";
            else if (path.equals(v + "/1784/content_publishing_limit"))
                json = "{\"data\":[{\"quota_usage\":" + quotaUsage + ",\"config\":{\"quota_total\":100}}]}";
            else if (path.equals(v + "/1784/media")) json = "{\"id\":\"cont1\"}";
            else if (path.equals(v + "/cont1"))
                json = statusPolls.incrementAndGet() < 3 ? "{\"status_code\":\"IN_PROGRESS\"}"
                        : "{\"status_code\":\"" + containerFinalStatus + "\",\"status\":\"Error: video too long\"}";
            else if (path.equals(v + "/1784/media_publish")) json = "{\"id\":\"media9\"}";
            else if (path.equals(v + "/media9/insights")) {
                if (insightsFail) { respond(ex, 400, "{\"error\":{\"message\":\"not ready\",\"code\":100}}"); return; }
                json = """
                        {"data":[{"name":"views","values":[{"value":4300}]},
                                 {"name":"reach","values":[{"value":3900}]},
                                 {"name":"likes","values":[{"value":210}]},
                                 {"name":"ig_reels_avg_watch_time","values":[{"value":12500}]},
                                 {"name":"ig_reels_video_view_total_time","values":[{"value":5400000}]}]}""";
            }
            else if (path.equals(v + "/media9"))
                json = "{\"id\":\"media9\",\"permalink\":\"https://www.instagram.com/reel/ABC/\",\"like_count\":200,\"comments_count\":8}";
            else { respond(ex, 404, "{\"error\":{\"message\":\"unknown " + path + "\"}}"); return; }
        }
        respond(ex, 200, json);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private InstagramPublisher publisher() {
        return new InstagramPublisher(api, new SignedUrlService(props), Duration.ofMillis(10), Duration.ofSeconds(5));
    }

    private static PublishItem item() {
        return new PublishItem(UUID.randomUUID(), Path.of("clip.mp4"), new PostMetadata("Mi clip", "Con #hashtag"), "1784");
    }

    @Test
    void oauth_exchanges_the_code_for_a_60_day_token_and_identifies_the_account() throws Exception {
        InstagramOAuthProvider provider = new InstagramOAuthProvider(props, api);

        OAuthTokens tokens = provider.exchangeCode("the-code#_", "https://x/cb");

        assertThat(received.get("/oauth/access_token")).contains("code=the-code", "grant_type=authorization_code")
                .doesNotContain("#_");
        assertThat(received.get("/access_token")).contains("grant_type=ig_exchange_token", "access_token=short");
        assertThat(tokens.accessToken()).isEqualTo("long");
        assertThat(tokens.refreshToken()).as("the long-lived token refreshes itself").isEqualTo("long");
        assertThat(tokens.accountId()).isEqualTo("1784");
        assertThat(tokens.displayName()).isEqualTo("@cliphive.demo");
        assertThat(tokens.expiresAt()).isAfter(Instant.now().plus(Duration.ofDays(59)));
        assertThat(provider.refreshMargin()).isEqualTo(Duration.ofDays(7));

        assertThat(provider.refresh("long").accessToken()).isEqualTo("long2");
    }

    @Test
    void authorization_url_asks_for_publishing_and_carries_the_state() {
        String url = URLDecoder.decode(new InstagramOAuthProvider(props, api)
                .authorizationUrl("st", "https://cliphive.example.com/admin/accounts/instagram/callback"), StandardCharsets.UTF_8);
        assertThat(url).contains("/oauth/authorize?", "client_id=app-id", "state=st",
                "instagram_business_content_publish", "response_type=code");
    }

    @Test
    void reel_is_created_from_the_signed_media_url_waited_for_and_published() throws Exception {
        UploadResult result = publisher().upload("tok", item());

        assertThat(result.externalId()).isEqualTo("media9");
        assertThat(result.url()).isEqualTo("https://www.instagram.com/reel/ABC/");
        String container = received.get("/" + InstagramApi.VERSION + "/1784/media");
        assertThat(container).contains("media_type=REELS", "video_url=https://cliphive-media.example.com/media/",
                "caption=Mi clip\n\nCon #hashtag");
        assertThat(statusPolls.get()).as("polled until FINISHED").isEqualTo(3);
        assertThat(received.get("/" + InstagramApi.VERSION + "/1784/media_publish")).contains("creation_id=cont1");
    }

    @Test
    void a_full_daily_quota_is_retried_later_and_a_processing_error_is_final() {
        quotaUsage = 100;
        assertThatThrownBy(() -> publisher().upload("tok", item()))
                .isInstanceOfSatisfying(PublishException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getMessage()).contains("Límite diario");
                });

        quotaUsage = 3;
        containerFinalStatus = "ERROR";
        assertThatThrownBy(() -> publisher().upload("tok", item()))
                .isInstanceOfSatisfying(PublishException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("video too long");
                });
    }

    @Test
    void insights_map_to_the_stats_schema_with_watch_time_converted() throws Exception {
        Map<String, Object> m = new InstagramStatsCollector(api).postMetrics("tok", "media9", LocalDate.now());

        assertThat(m).containsEntry("views", 4300L).containsEntry("reach", 3900L).containsEntry("likes", 210L);
        assertThat((Double) m.get("averageViewDurationSeconds")).isEqualTo(12.5);
        assertThat((Double) m.get("minutesWatched")).isEqualTo(90.0);
    }

    @Test
    void without_insights_the_like_and_comment_counters_are_kept() throws Exception {
        insightsFail = true;

        Map<String, Object> m = new InstagramStatsCollector(api).postMetrics("tok", "media9", LocalDate.now());

        assertThat(m).containsEntry("likes", 200L).containsEntry("comments", 8L).doesNotContainKey("views");
    }

    @Test
    void account_metrics_are_followers_and_media() throws Exception {
        assertThat(new InstagramStatsCollector(api).accountMetrics("tok"))
                .containsEntry("subscribers", 950L).containsEntry("videos", 12L);
    }
}
