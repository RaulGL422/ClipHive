package io.github.raulgl422.cliphive.server.publish.instagram;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.PostMetadata;
import io.github.raulgl422.cliphive.server.publish.ProcessingResult;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.PublishItem;
import io.github.raulgl422.cliphive.server.publish.Publisher;
import io.github.raulgl422.cliphive.server.publish.UploadResult;
import io.github.raulgl422.cliphive.server.service.SignedUrlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instagram Reels through the Instagram API with Instagram Login (DESIGN.md
 * §11.1, §11.2). Instagram fetches the video itself from a signed, short-lived
 * URL on the public media route, so nothing is uploaded from here: create the
 * REELS container, wait until Instagram has processed it, publish it. Unlike
 * TikTok and YouTube this publishes for real, without an audit (§11.4).
 */
@Component
public class InstagramPublisher implements Publisher {

    private static final Logger log = LoggerFactory.getLogger(InstagramPublisher.class);

    /** Instagram's caption limit (DESIGN.md §10.3, R10). */
    static final int MAX_CAPTION = 2200;

    private final InstagramApi api;
    private final SignedUrlService signedUrls;
    private final Duration pollInterval;
    private final Duration maxWait;

    @Autowired
    public InstagramPublisher(ObjectMapper objectMapper, SignedUrlService signedUrls) {
        this(InstagramApi.production(objectMapper), signedUrls, Duration.ofSeconds(10), Duration.ofMinutes(10));
    }

    InstagramPublisher(InstagramApi api, SignedUrlService signedUrls, Duration pollInterval, Duration maxWait) {
        this.api = api;
        this.signedUrls = signedUrls;
        this.pollInterval = pollInterval;
        this.maxWait = maxWait;
    }

    /** Well under the API's 100 a day, so the account doesn't look like spam (DESIGN.md §10.4). */
    @Override
    public int dailyLimit() {
        return 25;
    }

    @Override
    public Platform platform() {
        return Platform.INSTAGRAM;
    }

    @Override
    public UploadResult upload(String accessToken, PublishItem item) throws PublishException {
        String account = item.accountId();

        // 1. Daily limit (100 per 24 h): if reached, try again later instead of failing (§11.3)
        JsonNode limit = api.get(api.graph(account + "/content_publishing_limit"), Map.of(
                "fields", "quota_usage,config", "access_token", accessToken)).path("data").path(0);
        long used = limit.path("quota_usage").asLong(0);
        long total = limit.path("config").path("quota_total").asLong(Long.MAX_VALUE);
        if (used >= total) {
            throw PublishException.limitReached("Límite diario de publicaciones de Instagram alcanzado ("
                    + used + "/" + total + ")", Duration.ofHours(1));
        }

        // 2. REELS container pointing at the signed media URL
        Map<String, String> params = new LinkedHashMap<>();
        params.put("media_type", "REELS");
        params.put("video_url", signedUrls.generateMediaUrl(item.clipId()));
        params.put("caption", caption(item.metadata()));
        params.put("access_token", accessToken);
        String container = api.postForm(api.graph(account + "/media"), params).path("id").asText("");
        if (container.isBlank()) throw new PublishException("Instagram no devolvió el contenedor", true);

        // 3. Wait for Instagram to fetch and process the video
        waitUntilFinished(container, accessToken);

        // 4. Publish
        String mediaId = api.postForm(api.graph(account + "/media_publish"), Map.of(
                "creation_id", container, "access_token", accessToken)).path("id").asText("");
        if (mediaId.isBlank()) throw new PublishException("Instagram no devolvió el id de la publicación", true);

        // 5. Its link (only the API knows it); not worth failing over
        String permalink = null;
        try {
            permalink = api.get(api.graph(mediaId), Map.of("fields", "permalink", "access_token", accessToken))
                    .path("permalink").asText(null);
        } catch (PublishException e) {
            log.debug("Could not read permalink of {}: {}", mediaId, e.getMessage());
        }
        return new UploadResult(mediaId, permalink);
    }

    private void waitUntilFinished(String container, String accessToken) throws PublishException {
        Instant deadline = Instant.now().plus(maxWait);
        while (true) {
            JsonNode st = api.get(api.graph(container), Map.of(
                    "fields", "status_code,status", "access_token", accessToken));
            String code = st.path("status_code").asText("");
            switch (code) {
                case "FINISHED", "PUBLISHED" -> { return; }
                case "ERROR" -> throw new PublishException("Instagram no pudo procesar el vídeo: "
                        + st.path("status").asText("?"), false);
                case "EXPIRED" -> throw new PublishException("El contenedor de Instagram caducó", true);
                default -> { /* IN_PROGRESS */ }
            }
            if (Instant.now().isAfter(deadline)) {
                throw new PublishException("Instagram tarda demasiado en procesar el vídeo", true);
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PublishException("Interrumpido", true, e);
            }
        }
    }

    /** It's already live once media_publish returns; this only confirms it still exists. */
    @Override
    public ProcessingResult status(String accessToken, String externalId) throws PublishException {
        api.get(api.graph(externalId), Map.of("fields", "id", "access_token", accessToken));
        return ProcessingResult.done();
    }

    /** Instagram's link isn't derivable from the id — it's stored at upload time. */
    @Override
    public String publicUrl(String externalId) {
        return null;
    }

    static String caption(PostMetadata metadata) {
        String title = metadata.title() == null ? "" : metadata.title().strip();
        String description = metadata.description() == null ? "" : metadata.description().strip();
        String caption = description.isEmpty() ? title : title + "\n\n" + description;
        return caption.length() > MAX_CAPTION ? caption.substring(0, MAX_CAPTION) : caption;
    }
}
