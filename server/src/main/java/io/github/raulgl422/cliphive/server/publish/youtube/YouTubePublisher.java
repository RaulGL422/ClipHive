package io.github.raulgl422.cliphive.server.publish.youtube;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.PlaylistInfo;
import io.github.raulgl422.cliphive.server.publish.PostMetadata;
import io.github.raulgl422.cliphive.server.publish.ProcessingResult;
import io.github.raulgl422.cliphive.server.publish.PublishItem;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.Publisher;
import io.github.raulgl422.cliphive.server.publish.UploadResult;
import io.github.raulgl422.cliphive.server.service.SettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * YouTube Shorts through Data API v3, {@code videos.insert} resumable upload
 * (DESIGN.md §11.1). Uploads as <b>private</b>: an unaudited project can't
 * publish publicly (§11.4), so going public is a manual step in YouTube Studio.
 * A vertical video of up to 3 minutes is treated as a Short automatically.
 */
@Component
public class YouTubePublisher implements Publisher {

    /** YouTube rejects titles over 100 characters (DESIGN.md §10.3, R10). */
    static final int MAX_TITLE = 100;
    static final int MAX_DESCRIPTION = 5000;
    /** "People & Blogs" — a neutral default category. */
    static final String CATEGORY_ID = "22";

    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]+)");

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final String uploadBaseUrl;
    private final String apiBaseUrl;
    /** True once Google has audited the project (DESIGN.md §11.4): upload as public. */
    private final BooleanSupplier audited;

    @Autowired
    public YouTubePublisher(ObjectMapper objectMapper, SettingService settings) {
        this(objectMapper, "https://www.googleapis.com/upload/youtube/v3",
                "https://www.googleapis.com/youtube/v3", settings::youtubeAudited);
    }

    /** Base URLs overridable for tests against a local HTTP server. */
    YouTubePublisher(ObjectMapper objectMapper, String uploadBaseUrl, String apiBaseUrl) {
        this(objectMapper, uploadBaseUrl, apiBaseUrl, () -> false);
    }

    YouTubePublisher(ObjectMapper objectMapper, String uploadBaseUrl, String apiBaseUrl,
                     BooleanSupplier audited) {
        this.audited = audited;
        this.objectMapper = objectMapper;
        this.uploadBaseUrl = uploadBaseUrl;
        this.apiBaseUrl = apiBaseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Since December 2025 an upload costs ~100 quota units (was ~1,600) and has its
     * own bucket: the cap is prudence, not quota (DESIGN.md §10.4).
     */
    @Override
    public int dailyLimit() {
        return 10;
    }

    @Override
    public Platform platform() {
        return Platform.YOUTUBE;
    }

    @Override
    public UploadResult upload(String accessToken, PublishItem item) throws PublishException {
        Path video = item.file();
        PostMetadata metadata = item.metadata();
        long size;
        try {
            size = Files.size(video);
        } catch (IOException e) {
            throw new PublishException("No se encuentra el clip renderizado: " + video, false, e);
        }

        // 1. Open a resumable session with the metadata
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode snippet = body.putObject("snippet");
        snippet.put("title", title(metadata.title()));
        snippet.put("description", description(metadata.description()));
        snippet.put("categoryId", CATEGORY_ID);
        List<String> tags = tags(metadata.description());
        if (!tags.isEmpty()) {
            var tagArray = snippet.putArray("tags");
            tags.forEach(tagArray::add);
        }
        ObjectNode status = body.putObject("status");
        // Unaudited projects can only upload private videos (§11.4)
        status.put("privacyStatus", audited.getAsBoolean() ? "public" : "private");
        status.put("selfDeclaredMadeForKids", false);

        HttpRequest start = HttpRequest.newBuilder(URI.create(
                        uploadBaseUrl + "/videos?uploadType=resumable&part=snippet,status"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", "video/mp4")
                .header("X-Upload-Content-Length", String.valueOf(size))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> session = send(start);
        if (session.statusCode() != 200) throw apiError("abrir la subida", session);
        String uploadUrl = session.headers().firstValue("Location")
                .orElseThrow(() -> new PublishException("YouTube no devolvió la URL de subida", true));

        // 2. Send the file
        HttpRequest put;
        try {
            put = HttpRequest.newBuilder(URI.create(uploadUrl))
                    .timeout(Duration.ofMinutes(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "video/mp4")
                    .PUT(HttpRequest.BodyPublishers.ofFile(video))
                    .build();
        } catch (IOException e) {
            throw new PublishException("No se puede leer el clip: " + e.getMessage(), false, e);
        }
        HttpResponse<String> uploaded = send(put);
        if (uploaded.statusCode() != 200 && uploaded.statusCode() != 201) {
            throw apiError("subir el vídeo", uploaded);
        }
        String id = readJson(uploaded.body()).path("id").asText("");
        if (id.isBlank()) throw new PublishException("YouTube no devolvió el id del vídeo", true);
        return new UploadResult(id, null);
    }

    @Override
    public ProcessingResult status(String accessToken, String externalId) throws PublishException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        apiBaseUrl + "/videos?part=status,processingDetails&id=" + externalId))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) throw apiError("consultar el vídeo", response);

        JsonNode item = readJson(response.body()).path("items").path(0);
        if (item.isMissingNode()) return ProcessingResult.failed("El vídeo ya no existe en YouTube");
        JsonNode st = item.path("status");
        return switch (st.path("uploadStatus").asText("")) {
            case "processed" -> ProcessingResult.done();
            case "uploaded"  -> ProcessingResult.processing();
            case "failed"    -> ProcessingResult.failed("YouTube no pudo procesarlo: " + st.path("failureReason").asText("?"));
            case "rejected"  -> ProcessingResult.failed("YouTube lo rechazó: " + st.path("rejectionReason").asText("?"));
            case "deleted"   -> ProcessingResult.failed("El vídeo se borró en YouTube");
            default          -> ProcessingResult.processing();
        };
    }

    @Override
    public String publicUrl(String externalId) {
        return "https://youtu.be/" + externalId;
    }

    /** Private until the audit: you make it public by hand and confirm it (DESIGN.md §11.3). */
    @Override
    public boolean needsConfirmation() {
        return !audited.getAsBoolean();
    }

    /** One public playlist per source video, its clips appended in order (DESIGN.md §11.2). */
    @Override
    public boolean supportsPlaylists() {
        return true;
    }

    @Override
    public String addToPlaylist(String accessToken, String playlistId, PlaylistInfo info, String externalId)
            throws PublishException {
        String id = playlistId;
        if (id == null || id.isBlank()) {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode snippet = body.putObject("snippet");
            snippet.put("title", playlistTitle(info.title()));
            snippet.put("description", description(info.description()));
            body.putObject("status").put("privacyStatus", "public");
            HttpResponse<String> created = post(apiBaseUrl + "/playlists?part=snippet,status", accessToken, body);
            if (created.statusCode() != 200) throw apiError("crear la lista", created);
            id = readJson(created.body()).path("id").asText("");
            if (id.isBlank()) throw new PublishException("YouTube no devolvió el id de la lista", true);
        }
        ObjectNode item = objectMapper.createObjectNode();
        ObjectNode snippet = item.putObject("snippet");
        snippet.put("playlistId", id);
        ObjectNode resource = snippet.putObject("resourceId");
        resource.put("kind", "youtube#video");
        resource.put("videoId", externalId);
        HttpResponse<String> added = post(apiBaseUrl + "/playlistItems?part=snippet", accessToken, item);
        if (added.statusCode() != 200) throw apiError("añadir el clip a la lista", added);
        return id;
    }

    /** Playlist titles: at most 150 characters, no '<' or '>'. */
    static String playlistTitle(String raw) {
        return cleanTitle(raw, "Clips", MAX_PLAYLIST_TITLE);
    }

    static final int MAX_PLAYLIST_TITLE = 150;

    private HttpResponse<String> post(String url, String accessToken, ObjectNode body) throws PublishException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** YouTube rejects '<' and '>' in titles and titles over 100 characters. */
    static String title(String raw) {
        return cleanTitle(raw, "Clip", MAX_TITLE);
    }

    /** {@code raw} without '<' or '>', {@code fallback} when blank, cut to {@code max} with "…". */
    private static String cleanTitle(String raw, String fallback, int max) {
        String t = (raw == null || raw.isBlank()) ? fallback : raw.replace('<', ' ').replace('>', ' ').strip();
        return t.length() > max ? t.substring(0, max - 1).strip() + "…" : t;
    }

    /** The description's hashtags as video tags, without '#' (YouTube allows 500 characters in all). */
    static List<String> tags(String description) {
        List<String> tags = new ArrayList<>();
        if (description == null) return tags;
        int total = 0;
        var m = HASHTAG.matcher(description);
        while (m.find()) {
            String tag = m.group(1);
            if (tags.contains(tag) || total + tag.length() + 1 > 500) continue;
            tags.add(tag);
            total += tag.length() + 1;
        }
        return tags;
    }

    static String description(String raw) {
        String d = raw == null ? "" : raw.replace('<', ' ').replace('>', ' ');
        return d.length() > MAX_DESCRIPTION ? d.substring(0, MAX_DESCRIPTION) : d;
    }

    private PublishException apiError(String action, HttpResponse<String> response) {
        int code = response.statusCode();
        String body = YouTubeOAuthProvider.abbreviate(response.body());
        // 5xx and quota exhaustion are temporary; 401 means the token needs a refresh,
        // which the next attempt does. Anything else (400, 403 forbidden…) won't fix itself.
        boolean retryable = code >= 500 || code == 401 || code == 429
                || (code == 403 && body.contains("quotaExceeded"));
        if (code == 403 && (body.contains("insufficientPermissions") || body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT"))) {
            return new PublishException("YouTube no da permiso para " + action
                    + ": reconecta la cuenta de YouTube en Cuentas para conceder el permiso nuevo", false);
        }
        return new PublishException("YouTube HTTP " + code + " al " + action + ": " + body, retryable);
    }

    private HttpResponse<String> send(HttpRequest request) throws PublishException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PublishException("No se pudo contactar con YouTube: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Interrumpido", true, e);
        }
    }

    private JsonNode readJson(String body) throws PublishException {
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new PublishException("Respuesta de YouTube no válida", true, e);
        }
    }
}
