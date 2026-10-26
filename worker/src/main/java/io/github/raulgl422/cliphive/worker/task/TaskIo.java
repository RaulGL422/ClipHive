package io.github.raulgl422.cliphive.worker.task;

import io.github.raulgl422.cliphive.protocol.message.Progress;
import io.github.raulgl422.cliphive.protocol.message.TaskAssign;
import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.net.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

/**
 * Plumbing shared by the task handlers: the per-task work directory, the
 * signed-URL transfers and PROGRESS reports (docs/DESIGN.md §9, §15).
 */
public final class TaskIo {

    private static final Logger log = LoggerFactory.getLogger(TaskIo.class);

    /** One client for every task: connections to the storage host are reused. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private TaskIo() {}

    /**
     * {@code <dataDir>/tasks/<taskId>-<attempt>} — removed with {@link #deleteRecursively}
     * when the task ends. The attempt is in the name because the server can hand the
     * same task out again while an older attempt is still winding down here; sharing
     * the folder, the old run's cleanup would delete the new run's files.
     */
    static Path workDir(WorkerProperties props, TaskAssign assign) {
        return Path.of(props.getDataDir()).resolve("tasks")
                .resolve(assign.taskId() + "-" + assign.attempt());
    }

    static void progress(ServerConnection conn, TaskAssign assign, int percent, String phase) {
        conn.sendMessage(new Progress(assign.taskId(), assign.attempt(), percent, phase));
    }

    /** GETs {@code url} into {@code dest}; {@code what} names the file in errors. */
    static void download(String url, Path dest, Duration timeout, String what)
            throws IOException, InterruptedException {
        if (url == null || url.isBlank()) throw new IOException("No download URL for " + what);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(timeout).build();
        HttpResponse<Path> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() != 200) {
            throw new IOException("Download of " + what + " failed: HTTP " + resp.statusCode());
        }
    }

    /** PUTs {@code src} to {@code url}, streamed from disk; the server answers 204. */
    static void upload(String url, Path src, String contentType, Duration timeout, String what)
            throws IOException, InterruptedException {
        if (url == null || url.isBlank()) throw new IOException("No upload URL for " + what);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofFile(src))
                .header("Content-Type", contentType)
                .timeout(timeout)
                .build();
        HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() != 204) {
            throw new IOException("Upload of " + what + " failed: HTTP " + resp.statusCode());
        }
    }

    /** Deletes {@code dir} and everything in it; a missing dir is fine, failures are only logged. */
    public static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        int[] leftBehind = {0};
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) { leftBehind[0]++; }
            });
        } catch (IOException | UncheckedIOException e) { // the walk throws unchecked mid-stream
            log.warn("Could not clean up {}: {}", dir, e.getMessage());
            return;
        }
        if (leftBehind[0] > 0) {
            log.warn("Could not clean up {}: {} entries left behind", dir, leftBehind[0]);
        }
    }
}
