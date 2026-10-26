package io.github.raulgl422.cliphive.server.service.tool;

import java.nio.file.Path;

/**
 * Describes one audio chunk produced by {@link AudioChunker}.
 *
 * <p>{@link #startSeconds} and {@link #durationSeconds} are relative to the
 * <em>original</em> audio file, so the server can discard the overlap when
 * stitching transcripts (docs/DESIGN.md §4 step 5, R5).
 *
 * @param index           0-based chunk index
 * @param totalChunks     total number of chunks for this audio file
 * @param startSeconds    start offset in the source audio (seconds)
 * @param durationSeconds duration of this chunk including any overlap (seconds)
 * @param file            absolute path to the WAV chunk file
 */
public record ChunkInfo(int index, int totalChunks,
                        double startSeconds, double durationSeconds,
                        Path file) {

    public ChunkInfo {
        if (index < 0 || index >= totalChunks) {
            throw new IllegalArgumentException("chunk " + index + " of " + totalChunks);
        }
        if (durationSeconds <= 0) throw new IllegalArgumentException("chunk duration " + durationSeconds);
    }
}
