package io.github.raulgl422.cliphive.server.publish.tiktok;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.ProcessingResult;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.PublishItem;
import io.github.raulgl422.cliphive.server.publish.Publisher;
import io.github.raulgl422.cliphive.server.publish.UploadResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * TikTok through the Content Posting API, as a <b>draft</b> until the app is
 * audited (DESIGN.md §11.2, §11.4): the server uploads the file in chunks and
 * TikTok drops it in the creator's inbox, where it's finished and posted by
 * hand. The draft API takes no title or description — the "draft ready" email
 * carries them (§12.3).
 */
@Component
public class TikTokPublisher implements Publisher {

    /** Below this the file goes in one chunk (TikTok allows 5–64 MB chunks, or a whole file under 5 MB). */
    static final long CHUNK_SIZE = 10L * 1024 * 1024;

    private final TikTokApi api;

    @Autowired
    public TikTokPublisher(ObjectMapper objectMapper) {
        this(TikTokApi.production(objectMapper));
    }

    TikTokPublisher(TikTokApi api) {
        this.api = api;
    }

    @Override
    public Platform platform() {
        return Platform.TIKTOK;
    }

    @Override
    public boolean deliversAsDraft() {
        return true;
    }

    /**
     * The API takes at most 5 pending drafts in 24 h, but that no longer caps the
     * plan: past it you're emailed to upload by hand (DESIGN.md §10.4, §11.3).
     */
    @Override
    public int dailyLimit() {
        return 10;
    }

    /** {@code {chunkSize, chunkCount}}: whole files under 10 MB, else 10 MB chunks with the rest in the last one. */
    static long[] chunking(long size) {
        if (size < CHUNK_SIZE) return new long[]{size, 1};
        return new long[]{CHUNK_SIZE, size / CHUNK_SIZE};
    }

    @Override
    public UploadResult upload(String accessToken, PublishItem item) throws PublishException {
        Path file = item.file();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size == 0) throw new PublishException("El clip está vacío: " + file.getFileName(), false);
            long[] chunking = chunking(size);
            long chunkSize = chunking[0];
            long chunkCount = chunking[1];

            // 1. Draft upload: TikTok answers with the publish_id and where to PUT the file
            JsonNode data = api.postJson(api.v2("post/publish/inbox/video/init/"), accessToken, Map.of(
                    "source_info", Map.of(
                            "source", "FILE_UPLOAD",
                            "video_size", size,
                            "chunk_size", chunkSize,
                            "total_chunk_count", chunkCount))).path("data");
            String publishId = data.path("publish_id").asText("");
            String uploadUrl = data.path("upload_url").asText("");
            if (publishId.isBlank() || uploadUrl.isBlank()) {
                throw new PublishException("TikTok no devolvió dónde subir el vídeo", true);
            }

            // 2. Chunks in order; the last one takes whatever is left
            for (long i = 0; i < chunkCount; i++) {
                long first = i * chunkSize;
                long length = (i == chunkCount - 1) ? size - first : chunkSize;
                ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(length));
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer, first + buffer.position()) < 0) break;
                }
                api.putChunk(uploadUrl, buffer.array(), first, size);
            }
            return new UploadResult(publishId, null);
        } catch (IOException e) {
            throw new PublishException("No se pudo leer el clip: " + e.getMessage(), false, e);
        }
    }

    /** Done once the draft is in the inbox (or already posted from the app). */
    @Override
    public ProcessingResult status(String accessToken, String externalId) throws PublishException {
        JsonNode data = api.postJson(api.v2("post/publish/status/fetch/"), accessToken,
                Map.of("publish_id", externalId)).path("data");
        return switch (data.path("status").asText("")) {
            case "SEND_TO_USER_INBOX", "PUBLISH_COMPLETE" -> ProcessingResult.done();
            case "FAILED" -> ProcessingResult.failed("TikTok no pudo procesar el vídeo: "
                    + data.path("fail_reason").asText("motivo desconocido"));
            default -> ProcessingResult.processing(); // PROCESSING_UPLOAD
        };
    }

    /** A draft has no public link. */
    @Override
    public String publicUrl(String externalId) {
        return null;
    }
}
