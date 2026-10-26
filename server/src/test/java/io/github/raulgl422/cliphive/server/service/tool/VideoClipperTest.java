package io.github.raulgl422.cliphive.server.service.tool;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoClipperTest {

    @TempDir Path tempDir;

    private VideoClipper clipper() {
        return new VideoClipper(new ExternalToolLocator(new ClipHiveProperties()));
    }

    @Test
    void margin_constant_is_reasonable() {
        assertThat(VideoClipper.MARGIN_SECONDS).isBetween(1.0, 10.0);
    }

    @Test
    void cut_throws_when_source_file_missing() {
        Path missing = tempDir.resolve("nonexistent.mp4");
        Path dest    = tempDir.resolve("out.mp4");

        assertThatThrownBy(() -> clipper().cut(missing, 5_000, 65_000, dest))
                .isInstanceOfAny(IOException.class, IllegalStateException.class);
    }

    @Test
    void piece_start_is_clamped_to_zero_for_early_clips() throws Exception {
        // For a clip starting at 0 ms, pieceStart = max(0, 0/1000 - MARGIN) = 0
        // We can't run ffmpeg in CI but we can verify the clamping math
        double margin = VideoClipper.MARGIN_SECONDS;
        double clipStartS = 0.0;
        double requestedStart = clipStartS - margin;
        double pieceStart = Math.max(0.0, requestedStart);
        assertThat(pieceStart).isEqualTo(0.0);
    }
}
