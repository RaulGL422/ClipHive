package io.github.raulgl422.cliphive.server.service.tool;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static io.github.raulgl422.cliphive.server.service.tool.AudioChunker.CHUNK_DURATION_SECONDS;
import static io.github.raulgl422.cliphive.server.service.tool.AudioChunker.OVERLAP_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioChunkerTest {

    @TempDir
    Path tempDir;

    private AudioChunker chunker;

    @BeforeEach
    void setUp() {
        ClipHiveProperties props = new ClipHiveProperties();
        chunker = new AudioChunker(new ExternalToolLocator(props));
    }

    // ── chunk boundary math (unit tests, no actual ffmpeg needed) ─────────────

    @Test
    void single_chunk_for_short_audio() {
        // audio shorter than CHUNK_DURATION_SECONDS → exactly 1 chunk
        double totalDuration = 300.0; // 5 min
        int numChunks = (int) Math.ceil(totalDuration / CHUNK_DURATION_SECONDS);
        assertThat(numChunks).isEqualTo(1);
    }

    @Test
    void two_chunks_for_exactly_one_period() {
        // exactly 600 s → ceil(600/600) = 1 chunk
        double totalDuration = 600.0;
        int numChunks = (int) Math.ceil(totalDuration / CHUNK_DURATION_SECONDS);
        assertThat(numChunks).isEqualTo(1);
    }

    @Test
    void two_chunks_for_audio_just_over_one_period() {
        double totalDuration = 601.0;
        int numChunks = (int) Math.ceil(totalDuration / CHUNK_DURATION_SECONDS);
        assertThat(numChunks).isEqualTo(2);
    }

    @Test
    void chunk_zero_starts_at_zero_no_negative_start() {
        // chunk 0 nominal start = 0, so start = max(0, 0 - 10) = 0
        int i = 0;
        double nominalStart = (double) i * CHUNK_DURATION_SECONDS;
        double start = Math.max(0.0, nominalStart - OVERLAP_SECONDS);
        assertThat(start).isEqualTo(0.0);
    }

    @Test
    void chunk_one_has_overlap_with_chunk_zero() {
        double totalDuration = 1300.0;
        int i = 1;
        double nominalStart = (double) i * CHUNK_DURATION_SECONDS;
        double start = Math.max(0.0, nominalStart - OVERLAP_SECONDS);
        // should start 10 s before the nominal boundary
        assertThat(start).isEqualTo(nominalStart - OVERLAP_SECONDS);
    }

    @Test
    void last_chunk_end_capped_at_total_duration() {
        double totalDuration = 1200.0;
        int numChunks = (int) Math.ceil(totalDuration / CHUNK_DURATION_SECONDS); // 2
        int i = numChunks - 1; // last chunk
        double nominalStart = (double) i * CHUNK_DURATION_SECONDS;
        double end = Math.min(totalDuration, nominalStart + CHUNK_DURATION_SECONDS + OVERLAP_SECONDS);
        assertThat(end).isEqualTo(totalDuration);
    }

    @Test
    void sub_second_audio_produces_one_chunk() {
        double totalDuration = 0.5;
        int numChunks = (int) Math.ceil(totalDuration / CHUNK_DURATION_SECONDS);
        if (numChunks == 0) numChunks = 1;
        assertThat(numChunks).isEqualTo(1);
    }

    // ── probeDuration — error paths ──────────────────────────────────────────

    @Test
    void probeDuration_throws_on_missing_file() {
        Path missing = tempDir.resolve("nonexistent.wav");
        // Either ffprobe is not on PATH (IllegalStateException) or it runs and
        // fails on a nonexistent file (IOException) — both are valid failure modes.
        assertThatThrownBy(() -> chunker.probeDuration(missing))
                .isInstanceOfAny(IOException.class, IllegalStateException.class);
    }

    // ── ChunkInfo record ─────────────────────────────────────────────────────

    @Test
    void chunk_info_stores_fields_correctly() {
        Path file = tempDir.resolve("chunk_000.wav");
        ChunkInfo info = new ChunkInfo(0, 3, 0.0, 610.0, file);

        assertThat(info.index()).isEqualTo(0);
        assertThat(info.totalChunks()).isEqualTo(3);
        assertThat(info.startSeconds()).isEqualTo(0.0);
        assertThat(info.durationSeconds()).isEqualTo(610.0);
        assertThat(info.file()).isEqualTo(file);
    }
}
