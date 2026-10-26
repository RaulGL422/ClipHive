package io.github.raulgl422.cliphive.server.service.tool;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Resolves external binary paths (yt-dlp, ffmpeg, ffprobe, claude) from:
 * <ol>
 *   <li>An explicit path from {@link ClipHiveProperties} (set via env var, e.g. {@code CLIPHIVE_YTDLP_PATH}).</li>
 *   <li>PATH autodetection, with {@code .exe} suffix on Windows.</li>
 * </ol>
 * Multiplatform: paths via {@link Path}, no hand-written separators.
 * External processes must use the returned path in a ProcessBuilder argument list,
 * never through cmd/bash. See docs/DESIGN.md section 2.2.
 */
@Component
public class ExternalToolLocator {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    private final ClipHiveProperties props;

    public ExternalToolLocator(ClipHiveProperties props) {
        this.props = props;
    }

    /** Resolves the {@code yt-dlp} binary. */
    public Path ytdlp() {
        return locate("yt-dlp", props.getYtdlpPath());
    }

    /** Resolves the {@code ffmpeg} binary. */
    public Path ffmpeg() {
        return locate("ffmpeg", props.getFfmpegPath());
    }

    /** Resolves the {@code ffprobe} binary. */
    public Path ffprobe() {
        return locate("ffprobe", props.getFfprobePath());
    }

    /** Resolves the {@code claude} binary. */
    public Path claude() {
        return locate("claude", props.getClaudePath());
    }

    /**
     * @throws IllegalStateException if the tool is not found on PATH and no explicit path is set.
     */
    Path locate(String name, String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        String executable = IS_WINDOWS ? name + ".exe" : name;
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String rawDir : pathEnv.split(File.pathSeparator)) {
                // Some Windows PATH entries are quoted (e.g. "C:\Program Files\..."); strip them.
                String dir = rawDir.trim().replaceAll("^\"|\"$", "");
                if (dir.isBlank()) continue;
                try {
                    Path candidate = Path.of(dir).resolve(executable);
                    if (Files.isExecutable(candidate)) {
                        return candidate;
                    }
                } catch (InvalidPathException ignored) {
                    // Skip malformed PATH entries
                }
            }
        }
        throw new IllegalStateException(
                "'" + name + "' not found on PATH and no CLIPHIVE_" +
                name.toUpperCase().replace("-", "") + "_PATH configured");
    }
}
