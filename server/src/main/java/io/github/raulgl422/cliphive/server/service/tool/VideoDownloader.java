package io.github.raulgl422.cliphive.server.service.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Downloads a video with yt-dlp at max 1080p (docs/DESIGN.md §4, step 3).
 *
 * <p>Output: {@code <outputDir>/source.mp4} (always MP4 via {@code --merge-output-format}).
 * Uses ProcessBuilder with an explicit argument list — no shell. See docs/DESIGN.md §2.2.
 */
@Component
public class VideoDownloader {

    static final int TIMEOUT_MINUTES = 30;

    private static final Logger log = LoggerFactory.getLogger(VideoDownloader.class);

    private final ExternalToolLocator toolLocator;

    public VideoDownloader(ExternalToolLocator toolLocator) {
        this.toolLocator = toolLocator;
    }

    /**
     * yt-dlp format selector: best video up to 1080p with the audio track in
     * {@code audioLanguage} when there is one, falling back to the original audio.
     */
    static String formatSelector(String audioLanguage) {
        String original = "bestvideo[height<=1080]+bestaudio/best[height<=1080]";
        if (audioLanguage == null || audioLanguage.isBlank()) return original;
        String lang = audioLanguage.replaceAll("[^A-Za-z-]", "");
        return "bestvideo[height<=1080]+bestaudio[language^=" + lang + "]/" + original;
    }

    /**
     * Downloads {@code sourceUrl} into {@code outputDir/source.mp4}, preferring
     * the audio track in {@code audioLanguage} (a dub) when the source has one.
     * Sets the working directory to {@code outputDir} and uses a relative output template so
     * yt-dlp writes the file there without any OS-specific path escaping.
     *
     * @return the Path to the downloaded file (always {@code outputDir/source.mp4})
     * @throws IOException          if yt-dlp exits non-zero or fails to start
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public Path download(String sourceUrl, Path outputDir, String audioLanguage)
            throws IOException, InterruptedException {
        Files.createDirectories(outputDir);

        // Use relative output template and set working directory — avoids drive-letter
        // escaping issues on Windows (docs/DESIGN.md §2.2).
        ProcessBuilder pb = new ProcessBuilder(List.of(
                toolLocator.ytdlp().toString(),
                "--format", formatSelector(audioLanguage),
                "--merge-output-format", "mp4",
                "--output", "source.%(ext)s",
                "--no-playlist",
                "--no-write-info-json",
                "--quiet", "--no-warnings",
                "--", sourceUrl // after "--" the URL can never be read as an option
        ));
        pb.directory(outputDir.toFile());

        log.info("yt-dlp starting download: {}", sourceUrl);
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(pb, Duration.ofMinutes(TIMEOUT_MINUTES));
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " for: " + sourceUrl, e);
        }
        int exit = result.exitCode();
        String stderr = result.stderr();

        if (exit != 0) {
            log.error("yt-dlp failed (exit {}): {}", exit, stderr);
            throw new IOException("yt-dlp exited with code " + exit + ": " + truncate(stderr));
        }

        Path expected = outputDir.resolve("source.mp4");
        if (!Files.exists(expected)) {
            // yt-dlp occasionally uses a different extension despite --merge-output-format;
            // search for any source.* file as a fallback.
            try (var stream = Files.list(outputDir)) {
                return stream
                        .filter(f -> f.getFileName().toString().startsWith("source."))
                        .findFirst()
                        .orElseThrow(() -> new IOException(
                                "yt-dlp succeeded but no source.* file found in " + outputDir));
            }
        }
        return expected;
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(s.length() - 500) : s;
    }
}
