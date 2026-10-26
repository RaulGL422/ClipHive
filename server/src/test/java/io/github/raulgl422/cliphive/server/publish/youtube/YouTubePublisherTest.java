package io.github.raulgl422.cliphive.server.publish.youtube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.raulgl422.cliphive.server.publish.PlaylistInfo;
import io.github.raulgl422.cliphive.server.publish.PostMetadata;
import io.github.raulgl422.cliphive.server.publish.ProcessingResult;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.PublishItem;
import io.github.raulgl422.cliphive.server.publish.UploadResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Resumable upload and status checks against a local stand-in for the YouTube API. */
class YouTubePublisherTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private String base;
    private YouTubePublisher publisher;

    /** What the fake API received, by path. */
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> authHeaders = new ConcurrentHashMap<>();
    private volatile int startStatus = 200;
    private volatile String startBody = "";
    private volatile String statusResponse = "{\"items\":[]}";
    private final AtomicInteger itemCount = new AtomicInteger();
    private volatile int itemStatus = 200;
    private volatile String itemBody = "";

    @TempDir
    Path tmp;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/upload/videos", ex -> {
            record(ex, "start");
            if (startStatus != 200) {
                respond(ex, startStatus, startBody);
                return;
            }
            ex.getResponseHeaders().add("Location", base + "/session/abc");
            respond(ex, 200, "");
        });
        server.createContext("/session/abc", ex -> {
            record(ex, "put");
            respond(ex, 200, "{\"id\":\"vid123\"}");
        });
        server.createContext("/api/videos", ex -> respond(ex, 200, statusResponse));
        server.createContext("/api/playlists", ex -> {
            record(ex, "playlist");
            respond(ex, 200, "{\"id\":\"PL1\"}");
        });
        server.createContext("/api/playlistItems", ex -> {
            record(ex, "item-" + itemCount.incrementAndGet());
            respond(ex, itemStatus, itemStatus == 200 ? "{\"id\":\"item\"}" : itemBody);
        });
        server.start();
        publisher = new YouTubePublisher(json, base + "/upload", base + "/api");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void record(HttpExchange ex, String key) throws IOException {
        bodies.put(key, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authHeaders.put(key, String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        if (key.equals("start")) {
            bodies.put("start-length", String.valueOf(ex.getRequestHeaders().getFirst("X-Upload-Content-Length")));
            bodies.put("start-query", ex.getRequestURI().getQuery());
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void uploads_privately_with_metadata_and_returns_the_video_id() throws Exception {
        Path video = Files.write(tmp.resolve("clip.mp4"), new byte[1234]);

        UploadResult result = publisher.upload("tok", item(video, "Mi <clip>", "Descripción"));

        assertThat(result.externalId()).isEqualTo("vid123");
        assertThat(result.url()).as("derived from the id instead").isNull();
        JsonNode meta = json.readTree(bodies.get("start"));
        assertThat(meta.path("status").path("privacyStatus").asText()).isEqualTo("private");
        assertThat(meta.path("snippet").path("title").asText()).isEqualTo("Mi  clip"); // < > not allowed
        assertThat(meta.path("snippet").path("description").asText()).isEqualTo("Descripción");
        assertThat(bodies.get("start-query")).contains("uploadType=resumable");
        assertThat(bodies.get("start-length")).isEqualTo("1234");
        assertThat(bodies.get("put")).hasSize(1234);
        assertThat(authHeaders.get("put")).isEqualTo("Bearer tok");
    }

    @Test
    void hashtags_become_tags_and_an_audited_project_uploads_in_public() throws Exception {
        Path video = Files.write(tmp.resolve("clip.mp4"), new byte[10]);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        YouTubePublisher audited = new YouTubePublisher(json, base + "/upload", base + "/api", () -> true);

        audited.upload("tok", item(video, "Clip", "Qué partido.\n\n#Futbol #LaLiga #Futbol"));

        JsonNode meta = json.readTree(bodies.get("start"));
        assertThat(meta.path("status").path("privacyStatus").asText()).isEqualTo("public");
        assertThat(meta.path("snippet").path("tags").toString()).isEqualTo("[\"Futbol\",\"LaLiga\"]");
    }

    private static PublishItem item(Path video, String title, String description) {
        return new PublishItem(UUID.randomUUID(), video, new PostMetadata(title, description), "UC1");
    }

    @Test
    void long_titles_are_cut_to_100_characters() {
        String title = YouTubePublisher.title("x".repeat(150));
        assertThat(title).hasSize(YouTubePublisher.MAX_TITLE).endsWith("…");
    }

    @Test
    void quota_errors_are_retryable_and_bad_requests_are_not() throws Exception {
        Path video = Files.write(tmp.resolve("clip.mp4"), new byte[10]);

        startStatus = 403;
        startBody = "{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}";
        assertThatThrownBy(() -> publisher.upload("tok", item(video, "t", "d")))
                .isInstanceOfSatisfying(PublishException.class, e -> assertThat(e.isRetryable()).isTrue());

        startStatus = 400;
        startBody = "{\"error\":{\"message\":\"invalid title\"}}";
        assertThatThrownBy(() -> publisher.upload("tok", item(video, "t", "d")))
                .isInstanceOfSatisfying(PublishException.class, e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void status_maps_youtube_processing_states() throws Exception {
        statusResponse = "{\"items\":[{\"status\":{\"uploadStatus\":\"uploaded\"}}]}";
        assertThat(publisher.status("tok", "vid123").state()).isEqualTo(ProcessingResult.State.PROCESSING);

        statusResponse = "{\"items\":[{\"status\":{\"uploadStatus\":\"processed\"}}]}";
        assertThat(publisher.status("tok", "vid123").state()).isEqualTo(ProcessingResult.State.DONE);

        statusResponse = "{\"items\":[{\"status\":{\"uploadStatus\":\"rejected\",\"rejectionReason\":\"duplicate\"}}]}";
        ProcessingResult rejected = publisher.status("tok", "vid123");
        assertThat(rejected.state()).isEqualTo(ProcessingResult.State.FAILED);
        assertThat(rejected.detail()).contains("duplicate");

        statusResponse = "{\"items\":[]}";
        assertThat(publisher.status("tok", "vid123").state()).isEqualTo(ProcessingResult.State.FAILED);
    }

    @Test
    void the_first_clip_creates_the_public_playlist_and_the_next_ones_are_appended() throws Exception {
        var info = new PlaylistInfo("Mi <vídeo>", "Todos los clips");

        assertThat(publisher.addToPlaylist("tok", null, info, "vid1")).isEqualTo("PL1");
        JsonNode playlist = json.readTree(bodies.get("playlist"));
        assertThat(playlist.path("snippet").path("title").asText()).isEqualTo("Mi  vídeo");
        assertThat(playlist.path("status").path("privacyStatus").asText()).isEqualTo("public");
        JsonNode item = json.readTree(bodies.get("item-1"));
        assertThat(item.path("snippet").path("playlistId").asText()).isEqualTo("PL1");
        assertThat(item.path("snippet").path("resourceId").path("videoId").asText()).isEqualTo("vid1");

        // Second clip: the playlist exists, nothing is created
        bodies.remove("playlist");
        assertThat(publisher.addToPlaylist("tok", "PL1", info, "vid2")).isEqualTo("PL1");
        assertThat(bodies).doesNotContainKey("playlist");
        assertThat(json.readTree(bodies.get("item-2")).path("snippet").path("resourceId").path("videoId").asText())
                .isEqualTo("vid2");
    }

    @Test
    void a_connection_without_the_new_scope_says_to_reconnect() {
        itemStatus = 403;
        itemBody = "{\"error\":{\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}";
        var info = new PlaylistInfo("Mi vídeo", "");
        assertThatThrownBy(() -> publisher.addToPlaylist("tok", "PL1", info, "vid1"))
                .isInstanceOf(PublishException.class).hasMessageContaining("reconecta la cuenta de YouTube");
    }
}
