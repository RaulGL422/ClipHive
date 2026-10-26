package io.github.raulgl422.cliphive.worker.task;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.tool.WorkerToolLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real render filter graph through ffmpeg — a string-only test
 * missed that boxblur isn't in the LGPL build the installer ships.
 *
 * <p>Skipped when no ffmpeg is found (configured via
 * CLIPHIVE_WORKER_FFMPEG_PATH, bundled, or on the PATH).
 */
class RenderFilterFfmpegTest {

    private static final String MINIMAL_ASS = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 1080
            PlayResY: 1920

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,64,&H00FFFFFF,&H0000FFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,3,0,2,40,40,300,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,hola mundo
            """;

    @TempDir
    Path workDir;

    private Path ffmpeg;

    @BeforeEach
    void setUp() throws Exception {
        WorkerProperties props = new WorkerProperties();
        String configured = System.getenv("CLIPHIVE_WORKER_FFMPEG_PATH");
        if (configured != null) props.setFfmpegPath(configured);
        try {
            ffmpeg = new WorkerToolLocator(props).ffmpeg();
        } catch (IllegalStateException e) {
            ffmpeg = null;
        }
        assumeTrue(ffmpeg != null && Files.isExecutable(ffmpeg), "ffmpeg not available — skipping");

        // 8 s test source with audio, like the server's per-clip piece
        run(List.of(ffmpeg.toString(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "8", "-c:v", "mpeg4", "-c:a", "aac", "source.mp4"));
        Files.writeString(workDir.resolve("clip.ass"), MINIMAL_ASS, StandardCharsets.UTF_8);
    }

    @Test
    void single_range_graph_renders() throws Exception {
        render(RenderHandler.buildFilterComplex(List.<double[]>of(new double[]{1.0, 4.5}), 30));
    }

    @Test
    void multi_range_graph_renders() throws Exception {
        render(RenderHandler.buildFilterComplex(
                List.of(new double[]{0.5, 2.0}, new double[]{4.0, 6.25}), 60));
    }

    /** Same arguments RenderHandler uses, with a software encoder from the build. */
    private void render(String filterComplex) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg.toString(), "-y", "-v", "error",
                "-i", "source.mp4", "-filter_complex", filterComplex,
                "-map", "[vout]", "-map", "[aout]",
                "-c:v", softwareEncoder(), "-b:v", "8000000",
                "-c:a", "aac", "-b:a", "160k", "clip.mp4"));
        String out = run(cmd);
        Path clip = workDir.resolve("clip.mp4");
        assertThat(clip).as("ffmpeg output:\n" + out).exists();
        assertThat(Files.size(clip)).isPositive();
    }

    private String softwareEncoder() throws Exception {
        String encoders = run(List.of(ffmpeg.toString(), "-hide_banner", "-encoders"));
        return encoders.contains("libopenh264") ? "libopenh264" : "mpeg4";
    }

    private String run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .directory(workDir.toFile())   // the graph uses relative paths (ass=clip.ass)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("ffmpeg failed:\n" + out).isZero();
        return out;
    }
}
