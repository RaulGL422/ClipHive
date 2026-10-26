package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Where a job's files live under the data directory (DESIGN.md §15):
 * {@code jobs/<id>/clips/<NNN>/source.mp4} (the piece cut for the worker)
 * and {@code clip.mp4} (the rendered clip it uploads back).
 */
@Component
public class JobFiles {

    private final ClipHiveProperties props;

    public JobFiles(ClipHiveProperties props) {
        this.props = props;
    }

    public Path dataDir() {
        String d = props.getDataDir();
        return Path.of((d == null || d.isBlank()) ? "data" : d);
    }

    /** Parent of every job's folder ({@code jobs/}). */
    public Path jobsRoot() {
        return dataDir().resolve("jobs");
    }

    /** One job's folder ({@code jobs/<id>}). */
    public Path jobDir(UUID jobId) {
        return jobsRoot().resolve(jobId.toString());
    }

    public Path clipsDir(UUID jobId) {
        return jobDir(jobId).resolve("clips");
    }

    /** Folder of one clip; {@code clipIndex} is 0-based, the folder is {@code 000}, {@code 001}… */
    public Path clipDir(UUID jobId, int clipIndex) {
        return clipsDir(jobId).resolve("%03d".formatted(clipIndex));
    }

    /**
     * The downloaded source video ({@code jobs/<id>/source/}), or null when it
     * isn't there yet. yt-dlp may pick another extension than .mp4 now and then.
     */
    public Path sourceVideo(UUID jobId) throws IOException {
        Path dir = jobDir(jobId).resolve("source");
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".mp4") || name.endsWith(".mkv")
                                || name.endsWith(".webm") || name.endsWith(".ts");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    /** The rendered clip uploaded by the worker. */
    public Path renderedClip(UUID jobId, int clipIndex) {
        return clipDir(jobId, clipIndex).resolve("clip.mp4");
    }

    /** The rendered clip is still on disk (the cleanup hasn't taken it, §15). */
    public boolean renderedClipExists(UUID jobId, int clipIndex) {
        return Files.isRegularFile(renderedClip(jobId, clipIndex));
    }

    /**
     * {@code file} as stored in task parameters: relative to the data directory,
     * with forward slashes, so it's portable across restarts and platforms.
     */
    public String toStored(Path file) {
        return dataDir().toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    /** The file behind a path stored by {@link #toStored} (either slash accepted). */
    public Path fromStored(String stored) {
        Path path = dataDir();
        for (String part : stored.replace('\\', '/').split("/")) {
            if (!part.isEmpty()) path = path.resolve(part);
        }
        return path;
    }
}
