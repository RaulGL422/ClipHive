package io.github.raulgl422.cliphive.worker.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs the worker's external tools (ffmpeg, whisper-cli) so that none is ever
 * left running on its own (docs/DESIGN.md §9.2): killed on timeout, failure or
 * interrupt (a cancelled task), and on JVM exit — Windows doesn't end child
 * processes with their parent, and a live ffmpeg also keeps the task's files locked.
 */
public final class ToolProcess {

    private static final Logger log = LoggerFactory.getLogger(ToolProcess.class);

    /** What a failure message carries from the output: enough to see the error, small enough for TASK_FAILED. */
    static final int MAX_TAIL_CHARS = 4_000;
    private static final int TAIL_LINES = 30;

    private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> RUNNING.forEach(Process::destroyForcibly), "tool-process-cleanup"));
    }

    private ToolProcess() {}

    /** Starts {@code pb} and registers the process for the exit-time kill; pair with {@link #end}. */
    public static Process start(ProcessBuilder pb, String name) throws IOException {
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("Could not start " + name + ": " + e.getMessage(), e);
        }
        RUNNING.add(p);
        return p;
    }

    /** Kills {@code p} if it's still running, waits for it to exit, and unregisters it. */
    public static void end(Process p, String name) {
        try {
            if (!p.isAlive()) return;
            p.destroyForcibly();
            boolean exited;
            try {
                exited = p.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exited = !p.isAlive();
            }
            if (!exited) log.warn("{} (pid {}) still running 10 s after being killed", name, p.pid());
        } finally {
            RUNNING.remove(p);
        }
    }

    /**
     * Runs {@code cmd} in {@code workDir} with its output going to {@code logFile},
     * not a pipe: reading a pipe blocks until the process exits, so the timeout
     * could never fire and a cancel couldn't stop it.
     *
     * @throws IOException if it can't start, times out or exits non-zero — the
     *                     message ends with the last lines of its output
     */
    public static void runLogged(List<String> cmd, Path workDir, Path logFile, Duration timeout, String name)
            throws IOException, InterruptedException {
        Process p = start(new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile()), name);
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException(name + " timed out after " + timeout.toMinutes() + " min:\n" + tail(logFile));
            }
            if (p.exitValue() != 0) {
                throw new IOException(name + " exited with code " + p.exitValue() + ":\n" + tail(logFile));
            }
        } finally {
            end(p, name);
        }
    }

    /** Last {@value #TAIL_LINES} lines of {@code logFile}, at most {@value #MAX_TAIL_CHARS} chars; reads only its end. */
    static String tail(Path logFile) {
        try (SeekableByteChannel ch = Files.newByteChannel(logFile)) {
            long size = ch.size();
            int len = (int) Math.min(size, MAX_TAIL_CHARS);
            ByteBuffer buf = ByteBuffer.allocate(len);
            ch.position(size - len);
            while (buf.hasRemaining() && ch.read(buf) >= 0) { /* fill */ }
            List<String> lines = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8).lines().toList();
            // The first line may be cut in half when the file was longer than the window
            int from = Math.max(size > len && lines.size() > 1 ? 1 : 0, lines.size() - TAIL_LINES);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            log.debug("Could not read {}", logFile, e);
            return "(no output: " + e.getMessage() + ")";
        }
    }
}
