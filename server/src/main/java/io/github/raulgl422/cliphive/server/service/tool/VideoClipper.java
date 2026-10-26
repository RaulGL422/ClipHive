package io.github.raulgl422.cliphive.server.service.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Cuts a portion of the video source file using {@code ffmpeg -c copy} for
 * each clip (docs/DESIGN.md §4, step 10 / §9.2).
 *
 * <p>The cut is fast (no re-encode) but not frame-accurate because {@code -c
 * copy} snaps to the nearest keyframe. A generous margin is added on both
 * sides so the worker can later perform the exact trim ({@code trim}/
 * {@code atrim} filters) from the keyframe-aligned piece.
 *
 * <p>Uses ProcessBuilder with an explicit argument list — no shell.
 * See docs/DESIGN.md §2.2.
 *
 * <p>ffmpeg's {@code subtitles} filter breaks on Windows paths, but this
 * method uses {@code -c copy} only, so relative paths are not required here;
 * absolute paths are fine.
 */
@Component
public class VideoClipper {

    /**
     * Margin in seconds added before and after the requested clip range.
     * Absorbs keyframe-alignment drift in the {@code -c copy} cut.
     */
    static final double MARGIN_SECONDS = 5.0;

    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(VideoClipper.class);

    private final ExternalToolLocator toolLocator;

    public VideoClipper(ExternalToolLocator toolLocator) {
        this.toolLocator = toolLocator;
    }

    /**
     * Cuts the source video so that the worker only downloads the portion it
     * needs. The output file is written to {@code destFile}.
     *
     * @param sourceFile     the full downloaded video
     * @param clipStartMs    start of the clip in the original video (ms)
     * @param clipEndMs      end of the clip in the original video (ms)
     * @param destFile       where to write the cut piece
     * @return the actual {@code -ss} value used (may differ from
     *         {@code clipStartMs/1000 - MARGIN_SECONDS} when clamped to 0)
     * @throws IOException          if ffmpeg exits non-zero
     * @throws InterruptedException if interrupted
     */
    public double cut(Path sourceFile, long clipStartMs, long clipEndMs, Path destFile)
            throws IOException, InterruptedException {

        Files.createDirectories(destFile.getParent());

        double requestedStart = clipStartMs / 1000.0 - MARGIN_SECONDS;
        double pieceStart = Math.max(0.0, requestedStart);
        double requestedEnd   = clipEndMs / 1000.0 + MARGIN_SECONDS;
        double duration = requestedEnd - pieceStart;

        ProcessBuilder pb = new ProcessBuilder(
                toolLocator.ffmpeg().toString(),
                "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", pieceStart),
                "-t",  String.format(Locale.ROOT, "%.3f", duration),
                "-i",  sourceFile.toAbsolutePath().toString(),
                "-c",  "copy",
                destFile.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);

        ProcessRunner.Result result = ProcessRunner.run(pb, TIMEOUT);
        if (!result.ok()) throw new IOException("ffmpeg clip cut failed: " + result.stdout().strip());

        log.debug("Clip cut: [{}-{}ms] → {} (pieceStart={}s)", clipStartMs, clipEndMs,
                  destFile.getFileName(), String.format(Locale.ROOT, "%.3f", pieceStart));
        return pieceStart;
    }
}
