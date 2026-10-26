package io.github.raulgl422.cliphive.server.service.tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Runs an external tool (ffmpeg, ffprobe, yt-dlp, {@code claude -p}) with a
 * hard timeout — docs/DESIGN.md §2.2: argument list, never a shell.
 *
 * <p>stdout and stderr are drained on their own virtual threads while this
 * thread waits, so the timeout holds even when the child hangs with its
 * pipes open: reading a stream to the end before waiting would block forever
 * on a child that never exits. On timeout the child is killed.
 */
public final class ProcessRunner {

    /** What the tool printed and how it ended. With {@code redirectErrorStream} stderr is empty. */
    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    private static final Executor DRAINERS = task -> Thread.ofVirtual().start(task);

    private ProcessRunner() {}

    /** Runs {@code pb} with nothing on stdin. */
    public static Result run(ProcessBuilder pb, Duration timeout) throws IOException, InterruptedException {
        return run(pb, null, timeout);
    }

    /**
     * Runs {@code pb}, writes {@code stdin} (if any) and waits up to {@code timeout}.
     *
     * @throws IOException if the tool can't start or doesn't finish in time (it's killed then)
     */
    public static Result run(ProcessBuilder pb, byte[] stdin, Duration timeout)
            throws IOException, InterruptedException {
        String tool = toolName(pb);
        Process process = pb.start();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> drain(process.getInputStream()), DRAINERS);
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> drain(process.getErrorStream()), DRAINERS);
        // stdin too: a child that stops reading would block this thread on a full
        // pipe before the timeout starts. A child that exits without reading it
        // breaks the pipe; its exit code and stderr say why, so that's ignored.
        DRAINERS.execute(() -> {
            try (OutputStream in = process.getOutputStream()) {
                if (stdin != null) in.write(stdin);
            } catch (IOException ignored) {
            }
        });
        try {
            if (!process.waitFor(timeout)) {
                throw new IOException(tool + " timed out after " + describe(timeout));
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            kill(process);
            throw e;
        }
        return new Result(process.exitValue(), out.join(), err.join());
    }

    /**
     * Kills the child and whatever it started (yt-dlp runs ffmpeg): those would
     * keep writing files and hold the pipes, and with them the drain threads.
     */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** The file name of the executable, for messages ({@code C:\...\ffmpeg.exe} → {@code ffmpeg.exe}). */
    private static String toolName(ProcessBuilder pb) {
        if (pb.command().isEmpty()) return "process";
        Path name = Path.of(pb.command().getFirst()).getFileName();
        return name != null ? name.toString() : pb.command().getFirst();
    }

    private static String describe(Duration d) {
        return d.toMinutes() > 0 ? d.toMinutes() + " min" : d.toSeconds() + " s";
    }

    private static String drain(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";  // the stream closes when the child is killed; nothing more to read
        }
    }
}
