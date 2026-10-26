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
 * Extracts a WAV 16 kHz mono audio track from a video file using ffmpeg
 * (docs/DESIGN.md §4, step 4).
 *
 * <p>No video reencoding — cheap regardless of source resolution.
 * Output: {@code <audioDir>/audio.wav}.
 * Uses ProcessBuilder with an explicit argument list — no shell. See docs/DESIGN.md §2.2.
 */
@Component
public class AudioExtractor {

    static final int TIMEOUT_MINUTES = 15;

    private static final Logger log = LoggerFactory.getLogger(AudioExtractor.class);

    private final ExternalToolLocator toolLocator;

    public AudioExtractor(ExternalToolLocator toolLocator) {
        this.toolLocator = toolLocator;
    }

    /**
     * Extracts audio from {@code sourceFile} to {@code audioDir/audio.wav}.
     *
     * @return the Path to the extracted WAV file
     * @throws IOException          if ffmpeg exits non-zero or fails to start
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public Path extract(Path sourceFile, Path audioDir) throws IOException, InterruptedException {
        Files.createDirectories(audioDir);
        Path outputFile = audioDir.resolve("audio.wav");

        // Absolute paths are safe for -i and the output; only ffmpeg's subtitles filter
        // needs relative ones on Windows (docs/DESIGN.md §2.2).
        ProcessBuilder pb = new ProcessBuilder(List.of(
                toolLocator.ffmpeg().toString(),
                "-y",              // overwrite output without asking
                "-i", sourceFile.toAbsolutePath().toString(),
                "-vn",             // no video stream
                "-acodec", "pcm_s16le",
                "-ar", "16000",    // 16 kHz
                "-ac", "1",        // mono
                outputFile.toAbsolutePath().toString()
        ));
        pb.redirectErrorStream(true);

        log.info("Extracting audio: {} → {}", sourceFile.getFileName(), outputFile.getFileName());
        // ffmpeg mixes its progress/metadata output into stderr (redirected to stdout here).
        ProcessRunner.Result result = ProcessRunner.run(pb, Duration.ofMinutes(TIMEOUT_MINUTES));
        if (!result.ok()) {
            log.error("ffmpeg audio extraction failed (exit {}): {}", result.exitCode(), result.stdout());
            throw new IOException("ffmpeg exited with code " + result.exitCode());
        }

        return outputFile;
    }
}
