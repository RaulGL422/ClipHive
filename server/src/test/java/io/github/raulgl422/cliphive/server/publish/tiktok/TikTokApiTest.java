package io.github.raulgl422.cliphive.server.publish.tiktok;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.publish.OAuthTokens;
import io.github.raulgl422.cliphive.server.publish.PostMetadata;
import io.github.raulgl422.cliphive.server.publish.ProcessingResult;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.PublishItem;
import io.github.raulgl422.cliphive.server.publish.UploadResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OAuth, draft upload in chunks, status and account stats against a local
 * stand-in for TikTok's Open API (open.tiktokapis.com).
 */
class TikTokApiTest {

    @TempDir Path tmp;

    private HttpServer server;
    private TikTokApi api;
    private ClipHiveProperties props;
    private String base;

    /** Request bodies/queries the fake received, by path. */
    private final Map<String, String> received = new ConcurrentHashMap<>();
    private final Map<String, String> authHeaders = new ConcurrentHashMap<>();
    /** Content-Range and size of every chunk PUT. */
    private final List<String> chunks = new CopyOnWriteArrayList<>();
    private volatile String initError = null;
    private volatile String status = "SEND_TO_USER_INBOX";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
        api = new TikTokApi(JsonMapper.builder().build(), base, base);

        props = new ClipHiveProperties();
        props.setTiktokClientKey("ck");
        props.setTiktokClientSecret("cs");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        byte[] raw = ex.getRequestBody().readAllBytes();
        String query = ex.getRequestURI().getRawQuery();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) authHeaders.put(path, auth);

        if (path.equals("/upload")) {
            String range = ex.getRequestHeaders().getFirst("Content-Range");
            chunks.add(range + " len=" + raw.length);
            long total = Long.parseLong(range.substring(range.indexOf('/') + 1));
            long last = Long.parseLong(range.substring(range.indexOf('-') + 1, range.indexOf('/')));
            ex.sendResponseHeaders(last + 1 == total ? 201 : 206, -1);
            ex.close();
            return;
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        received.put(path, URLDecoder.decode(body.isEmpty() && query != null ? query : body, StandardCharsets.UTF_8));
        switch (path) {
            case "/v2/oauth/token/" -> respond(ex, 200, received.get(path).contains("grant_type=refresh_token")
                    ? "{\"access_token\":\"act2\",\"expires_in\":86400,\"refresh_token\":\"rft2\",\"open_id\":\"oid\"}"
                    : "{\"access_token\":\"act\",\"expires_in\":86400,\"refresh_token\":\"rft\","
                      + "\"refresh_expires_in\":31536000,\"open_id\":\"oid\",\"scope\":\"user.info.basic,video.upload\"}");
            case "/v2/user/info/" -> respond(ex, 200, """
                    {"data":{"user":{"open_id":"oid","display_name":"Clips de demostración","follower_count":321,
                     "likes_count":4500,"video_count":17}},"error":{"code":"ok","message":""}}""");
            case "/v2/post/publish/inbox/video/init/" -> {
                if (initError != null) {
                    respond(ex, 403, "{\"error\":{\"code\":\"" + initError + "\",\"message\":\"nope\"}}");
                } else {
                    respond(ex, 200, "{\"data\":{\"publish_id\":\"v_inbox_file~1\",\"upload_url\":\"" + base
                            + "/upload?sig=1\"},\"error\":{\"code\":\"ok\"}}");
                }
            }
            case "/v2/post/publish/status/fetch/" -> respond(ex, 200, "{\"data\":{\"status\":\"" + status
                    + "\",\"fail_reason\":\"duration_check_failed\"},\"error\":{\"code\":\"ok\"}}");
            default -> respond(ex, 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"" + path + "\"}}");
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private PublishItem item(Path file) {
        return new PublishItem(UUID.randomUUID(), file, new PostMetadata("Mi clip", "Con #hashtag"), "oid");
    }

    @Test
    void oauth_exchanges_the_code_and_rotates_the_refresh_token() throws Exception {
        TikTokOAuthProvider provider = new TikTokOAuthProvider(props, api);

        OAuthTokens tokens = provider.exchangeCode("the-code", "https://x/cb");

        assertThat(received.get("/v2/oauth/token/")).contains("client_key=ck", "client_secret=cs", "code=the-code",
                "grant_type=authorization_code", "redirect_uri=https://x/cb");
        assertThat(tokens.accessToken()).isEqualTo("act");
        assertThat(tokens.refreshToken()).isEqualTo("rft");
        assertThat(tokens.accountId()).isEqualTo("oid");
        assertThat(tokens.displayName()).isEqualTo("Clips de demostración");
        assertThat(tokens.expiresAt()).isBetween(Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofHours(25)));
        assertThat(authHeaders.get("/v2/user/info/")).isEqualTo("Bearer act");

        OAuthTokens fresh = provider.refresh("rft");
        assertThat(received.get("/v2/oauth/token/")).contains("grant_type=refresh_token", "refresh_token=rft");
        assertThat(fresh.accessToken()).isEqualTo("act2");
        assertThat(fresh.refreshToken()).as("TikTok may rotate it").isEqualTo("rft2");
    }

    @Test
    void a_rejected_code_is_a_final_error() {
        server.removeContext("/");
        server.createContext("/", ex -> respond(ex, 200,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Authorization code is expired.\",\"log_id\":\"x\"}"));
        assertThatThrownBy(() -> new TikTokOAuthProvider(props, api).exchangeCode("old", "https://x/cb"))
                .isInstanceOf(PublishException.class)
                .hasMessageContaining("Authorization code is expired")
                .matches(e -> !((PublishException) e).isRetryable());
    }

    @Test
    void authorization_url_asks_for_drafts_and_carries_the_state() {
        String url = URLDecoder.decode(new TikTokOAuthProvider(props, api)
                .authorizationUrl("st", "https://cliphive.example.com/admin/accounts/tiktok/callback"), StandardCharsets.UTF_8);
        assertThat(url).startsWith(base + "/v2/auth/authorize/?")
                .contains("client_key=ck", "state=st", "response_type=code", "video.upload", "user.info.stats",
                        "redirect_uri=https://cliphive.example.com/admin/accounts/tiktok/callback")
                .doesNotContain("video.publish");
    }

    @Test
    void chunking_follows_tiktoks_rules() {
        long mb = 1024 * 1024;
        assertThat(TikTokPublisher.chunking(3 * mb)).as("under 5 MB: whole file").containsExactly(3 * mb, 1);
        assertThat(TikTokPublisher.chunking(9 * mb)).containsExactly(9 * mb, 1);
        assertThat(TikTokPublisher.chunking(10 * mb)).containsExactly(10 * mb, 1);
        assertThat(TikTokPublisher.chunking(57 * mb + 123)).as("rest goes in the last chunk")
                .containsExactly(10 * mb, 5);
    }

    @Test
    void a_draft_is_uploaded_in_order_in_chunks() throws Exception {
        long size = 21L * 1024 * 1024 + 7;
        Path file = tmp.resolve("clip.mp4");
        Files.write(file, new byte[(int) size]);

        UploadResult result = new TikTokPublisher(api).upload("act", item(file));

        assertThat(result.externalId()).isEqualTo("v_inbox_file~1");
        assertThat(result.url()).isNull();
        assertThat(received.get("/v2/post/publish/inbox/video/init/"))
                .contains("\"source\":\"FILE_UPLOAD\"", "\"video_size\":" + size,
                        "\"chunk_size\":" + TikTokPublisher.CHUNK_SIZE, "\"total_chunk_count\":2")
                .as("the draft API takes no title").doesNotContain("Mi clip");
        assertThat(authHeaders.get("/v2/post/publish/inbox/video/init/")).isEqualTo("Bearer act");
        long chunk = TikTokPublisher.CHUNK_SIZE;
        assertThat(chunks).containsExactly(
                "bytes 0-" + (chunk - 1) + "/" + size + " len=" + chunk,
                "bytes " + chunk + "-" + (size - 1) + "/" + size + " len=" + (size - chunk));
    }

    @Test
    void too_many_pending_drafts_postpones_instead_of_failing() throws Exception {
        initError = "spam_risk_too_many_pending_share";
        Path file = tmp.resolve("clip.mp4");
        Files.write(file, new byte[1024]);

        assertThatThrownBy(() -> new TikTokPublisher(api).upload("act", item(file)))
                .isInstanceOf(PublishException.class)
                .satisfies(e -> assertThat(((PublishException) e).retryAfter()).isEqualTo(Duration.ofHours(1)));

        initError = "spam_risk_user_banned_from_posting";
        assertThatThrownBy(() -> new TikTokPublisher(api).upload("act", item(file)))
                .satisfies(e -> {
                    assertThat(((PublishException) e).isRetryable()).isFalse();
                    assertThat(((PublishException) e).retryAfter()).isNull();
                });
    }

    @Test
    void status_is_done_once_the_draft_is_in_the_inbox() throws Exception {
        TikTokPublisher publisher = new TikTokPublisher(api);
        assertThat(publisher.deliversAsDraft()).isTrue();

        status = "PROCESSING_UPLOAD";
        assertThat(publisher.status("act", "p1").state()).isEqualTo(ProcessingResult.State.PROCESSING);
        status = "SEND_TO_USER_INBOX";
        assertThat(publisher.status("act", "p1").state()).isEqualTo(ProcessingResult.State.DONE);
        assertThat(received.get("/v2/post/publish/status/fetch/")).contains("\"publish_id\":\"p1\"");
        status = "FAILED";
        ProcessingResult failed = publisher.status("act", "p1");
        assertThat(failed.state()).isEqualTo(ProcessingResult.State.FAILED);
        assertThat(failed.detail()).contains("duration_check_failed");
    }

    @Test
    void account_stats_map_to_the_common_keys() throws Exception {
        TikTokStatsCollector stats = new TikTokStatsCollector(api);
        assertThat(stats.accountMetrics("act"))
                .containsEntry("subscribers", 321L).containsEntry("likes", 4500L).containsEntry("videos", 17L);
        assertThat(received.get("/v2/user/info/")).contains("fields=follower_count,likes_count,video_count");
        assertThat(stats.postMetrics("act", "p1", null)).isEmpty();
    }
}
