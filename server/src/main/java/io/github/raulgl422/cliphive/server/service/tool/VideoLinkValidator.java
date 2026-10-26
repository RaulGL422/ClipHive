package io.github.raulgl422.cliphive.server.service.tool;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a video URL by running {@code yt-dlp --dump-json --simulate --no-playlist}
 * and parsing its JSON output (docs/DESIGN.md section 4, step 2).
 *
 * <p>Uses ProcessBuilder with an explicit argument list — no shell. See docs/DESIGN.md §2.2.
 * Stderr is drained on a background thread so the subprocess never blocks on a full pipe.
 */
@Component
public class VideoLinkValidator {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final ExternalToolLocator toolLocator;
    private final ObjectMapper objectMapper;

    public VideoLinkValidator(ExternalToolLocator toolLocator, ObjectMapper objectMapper) {
        this.toolLocator = toolLocator;
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(String url) {
        Path ytdlp;
        try {
            ytdlp = toolLocator.ytdlp();
        } catch (IllegalStateException e) {
            return new ValidationResult.Failure("yt-dlp not found: " + e.getMessage());
        }

        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(new ProcessBuilder(
                    ytdlp.toString(), "--dump-json", "--simulate", "--no-playlist",
                    "--", url // after "--" the URL can never be read as an option
            ), TIMEOUT);
        } catch (IOException e) {
            return new ValidationResult.Failure("yt-dlp failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ValidationResult.Failure("Interrupted while waiting for yt-dlp");
        }
        int exitCode = result.exitCode();
        String stdout = result.stdout();
        String stderr = result.stderr();

        if (exitCode != 0) {
            return new ValidationResult.Failure(extractError(stderr));
        }

        return parseOutput(stdout);
    }

    ValidationResult parseOutput(String json) {
        try {
            JsonNode root = objectMapper.readTree(json.strip());

            JsonNode titleNode = root.path("title");
            String title = (!titleNode.isMissingNode() && !titleNode.isNull()) ? titleNode.stringValue() : null;

            JsonNode extractorNode = root.path("extractor");
            String extractor = (!extractorNode.isMissingNode() && !extractorNode.isNull())
                    ? extractorNode.stringValue() : "unknown";

            JsonNode durationNode = root.path("duration");
            int durationSeconds = (!durationNode.isMissingNode() && !durationNode.isNull())
                    ? (int) Math.round(durationNode.asDouble())
                    : 0;

            JsonNode fpsNode = root.path("fps");
            Integer fps = (!fpsNode.isMissingNode() && !fpsNode.isNull())
                    ? (int) Math.round(fpsNode.asDouble())
                    : null;

            JsonNode widthNode = root.path("width");
            JsonNode heightNode = root.path("height");
            String resolution = (!widthNode.isMissingNode() && !heightNode.isMissingNode()
                    && !widthNode.isNull() && !heightNode.isNull())
                    ? widthNode.asInt() + "x" + heightNode.asInt()
                    : null;

            // Not read yet: the category/game_name (YouTube "categories[0]", Twitch/Kick "game_name")
            // that DESIGN.md §4 step 2 lists, so VideoJob.category stays null.

            // Audio tracks on offer: YouTube lists dubbed/auto-dubbed tracks as separate
            // audio-only formats with their own "language".
            Set<String> audioLanguages = new LinkedHashSet<>();
            for (JsonNode f : root.path("formats")) {
                String lang = f.path("language").isNull() ? "" : f.path("language").asText("");
                boolean hasAudio = !"none".equals(f.path("acodec").asText("none"));
                if (hasAudio && !lang.isBlank()) audioLanguages.add(lang);
            }

            // Who made the video, to credit them in the clip descriptions (DESIGN.md §10.3)
            String channel = null;
            for (String field : List.of("channel", "uploader", "creator")) {
                String value = root.path(field).isNull() ? "" : root.path(field).asText("");
                if (!value.isBlank()) {
                    channel = value.strip();
                    break;
                }
            }

            return new ValidationResult.Success(title, extractor, durationSeconds, fps, resolution,
                    List.copyOf(audioLanguages), channel);

        } catch (JacksonException e) {
            return new ValidationResult.Failure("Failed to parse yt-dlp output: " + e.getMessage());
        }
    }

    static String extractError(String stderr) {
        return stderr.lines()
                .filter(l -> l.startsWith("ERROR:"))
                .map(l -> l.substring("ERROR:".length()).strip())
                .findFirst()
                .orElse(stderr.isBlank() ? "yt-dlp exited with error" : stderr.strip());
    }
}
