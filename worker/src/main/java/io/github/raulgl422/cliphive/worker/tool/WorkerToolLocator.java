package io.github.raulgl422.cliphive.worker.tool;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Resolves external binary paths for the worker (ffmpeg, ffprobe,
 * whisper-cli). Strategy (docs/DESIGN.md §2.2):
 * <ol>
 *   <li>Explicit path from {@link WorkerProperties} (set via env var or YAML).
 *   <li>The {@code bin/} directory next to the worker jar — where the
 *       installer bundles ffmpeg, ffprobe and whisper-cli (docs/DESIGN.md §16.4).
 *   <li>PATH autodetection with {@code .exe} suffix on Windows.
 * </ol>
 *
 * <p>Never hard-codes separators or calls through a shell — all callers must
 * use the returned {@link Path} in a {@link ProcessBuilder} argument list.
 */
public class WorkerToolLocator {

    private static final Logger log = LoggerFactory.getLogger(WorkerToolLocator.class);
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    private final WorkerProperties props;
    private final Path bundledBinDir;

    public WorkerToolLocator(WorkerProperties props) {
        this(props, defaultBundledBinDir());
    }

    WorkerToolLocator(WorkerProperties props, Path bundledBinDir) {
        this.props = props;
        this.bundledBinDir = bundledBinDir;
    }

    public Path ffmpeg() {
        return locate("ffmpeg", props.getFfmpegPath());
    }

    public Path ffprobe() {
        return locate("ffprobe", props.getFfprobePath());
    }

    public Path whisperCli() {
        return locate("whisper-cli", props.getWhisperPath());
    }

    Path locate(String name, String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            // Checked here so a typo is reported as such, not as every task failing later
            Path configured;
            try {
                configured = Path.of(configuredPath.strip());
            } catch (InvalidPathException e) {
                throw new IllegalStateException("configured path for '" + name + "' is not a valid path: "
                        + configuredPath, e);
            }
            // A bare name ("ffmpeg") means "this command": look it up on PATH like ProcessBuilder would
            if (configured.getParent() == null && !configured.isAbsolute()) {
                String bare = configured.toString();
                Path onPath = findOnPath(IS_WINDOWS && !bare.toLowerCase().endsWith(".exe") ? bare + ".exe" : bare);
                if (onPath != null) return onPath;
                throw new IllegalStateException("configured command for '" + name + "' ('" + bare
                        + "') is not on PATH");
            }
            if (!Files.isExecutable(configured)) {
                throw new IllegalStateException("configured path for '" + name + "' is not an executable file: "
                        + configured);
            }
            return configured;
        }
        String executable = IS_WINDOWS ? name + ".exe" : name;
        if (bundledBinDir != null) {
            Path bundled = bundledBinDir.resolve(executable);
            if (Files.isExecutable(bundled)) {
                return bundled;
            }
        }
        Path onPath = findOnPath(executable);
        if (onPath != null) return onPath;
        throw new IllegalStateException(
                "'" + name + "' not found on PATH and no explicit path configured");
    }

    /** {@code executable} in one of the PATH directories, or null. */
    private static Path findOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String rawDir : pathEnv.split(File.pathSeparator)) {
            String dir = rawDir.trim().replaceAll("^\"|\"$", "");
            if (dir.isBlank()) continue;
            try {
                Path candidate = Path.of(dir).resolve(executable);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (InvalidPathException ignored) {
            }
        }
        return null;
    }

    /**
     * {@code bin/} next to the jar this class was loaded from. In a jpackage
     * install the jar sits in {@code app/} (Windows) or {@code lib/app/}
     * (Linux) with the bundled binaries in a {@code bin/} sibling.
     */
    private static Path defaultBundledBinDir() {
        try {
            var source = WorkerToolLocator.class.getProtectionDomain().getCodeSource();
            if (source == null) return null;
            Path location = Path.of(source.getLocation().toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            return dir == null ? null : dir.resolve("bin");
        } catch (Exception e) {
            log.debug("Could not resolve bundled bin directory: {}", e.getMessage());
            return null;
        }
    }
}
