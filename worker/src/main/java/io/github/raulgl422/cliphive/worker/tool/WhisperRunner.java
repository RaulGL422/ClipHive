package io.github.raulgl422.cliphive.worker.tool;

import io.github.raulgl422.cliphive.worker.model.ModelDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs {@code whisper-cli} as an external process for a single audio chunk
 * (docs/DESIGN.md §9.1).
 *
 * <p>Flags used:
 * <ul>
 *   <li>{@code -m <model>} — model file path
 *   <li>{@code -f <audio>} — input WAV
 *   <li>{@code -ojf} — output JSON with per-word timestamps
 *   <li>{@code -bs 1 -bo 1} — greedy decoding (faster)
 *   <li>{@code -l <lang>} — language code, or {@code auto}
 *   <li>{@code -of <output>} — base path for output files (without extension)
 *   <li>{@code -pp} — print progress
 * </ul>
 *
 * <p>whisper-cli writes {@code <output>.json} next to the audio file.
 * This class moves it to the caller-supplied {@code transcriptJson} path.
 *
 * <p>Progress is parsed from whisper-cli's stderr lines which contain
 * {@code whisper_full_with_state: progress = N%}.
 */
public class WhisperRunner {

    private static final Logger log = LoggerFactory.getLogger(WhisperRunner.class);

    /** Timeout for a single chunk transcription: generous, a slow CPU can take long. */
    private static final int TIMEOUT_MINUTES = 120;

    private static final int TAIL_LINES = 30;

    /** Matches "whisper_full_with_state: progress = 42%" */
    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("progress\\s*=\\s*(\\d+)%", Pattern.CASE_INSENSITIVE);

    private final WorkerToolLocator toolLocator;
    private final Path modelsDir;

    public WhisperRunner(WorkerToolLocator toolLocator, Path modelsDir) {
        this.toolLocator = toolLocator;
        this.modelsDir = modelsDir;
    }

    /**
     * Transcribes {@code audioFile} and writes the JSON result to
     * {@code transcriptJson}.
     *
     * @param audioFile      input 16 kHz mono WAV
     * @param transcriptJson destination path for the JSON output
     * @param language       BCP-47 language code or {@code "auto"}
     * @param model          model name, e.g. {@code "small"}, {@code "medium"}
     * @param progressCb     receives [0..100] progress updates; may be called
     *                       multiple times, from the thread reading whisper's output
     * @throws IOException          if whisper-cli fails or output is missing
     * @throws InterruptedException if the calling thread is interrupted
     */
    public void run(Path audioFile, Path transcriptJson,
                    String language, String model,
                    IntConsumer progressCb)
            throws IOException, InterruptedException {

        Path modelFile = resolveModel(model);
        // whisper-cli -of writes <base>.json; we put it next to the audio
        String outputBase = audioFile.getParent().resolve("whisper_out").toString();

        List<String> cmd = buildCommand(audioFile, modelFile, language, outputBase);
        log.debug("whisper-cli command: {}", cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(audioFile.getParent().toFile());

        // Registered so a closed worker doesn't leave whisper transcribing on its own
        Process p = ToolProcess.start(pb, "whisper-cli");
        // Output is read on its own thread: reading it here would block until
        // whisper exits, so a hung whisper could be neither timed out nor
        // cancelled. This thread only waits on the process, which an interrupt ends.
        Deque<String> tail = new ArrayDeque<>();
        Thread reader = Thread.ofVirtual().name("whisper-output").start(() -> readOutput(p, tail, progressCb));
        try {
            if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw new IOException("whisper-cli timed out after " + TIMEOUT_MINUTES + " minutes:\n" + tail(tail));
            }
            reader.join(Duration.ofSeconds(5)); // the last progress lines
            if (p.exitValue() != 0) {
                throw new IOException("whisper-cli exited with code " + p.exitValue() + ":\n" + tail(tail));
            }
        } finally {
            // Never leave whisper running on its own (cancel, error, timeout).
            ToolProcess.end(p, "whisper-cli");
        }

        // whisper-cli writes <outputBase>.json
        Path written = Path.of(outputBase + ".json");
        if (!Files.exists(written)) {
            throw new IOException("whisper-cli did not produce output at " + written);
        }
        Files.move(written, transcriptJson, StandardCopyOption.REPLACE_EXISTING);
        progressCb.accept(100);
    }

    /** Logs whisper's output, keeps its last lines for errors and turns progress lines into callbacks. */
    private static void readOutput(Process p, Deque<String> tail, IntConsumer progressCb) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("whisper: {}", line);
                synchronized (tail) {
                    tail.addLast(line);
                    if (tail.size() > TAIL_LINES) tail.removeFirst();
                }
                Matcher m = PROGRESS_PATTERN.matcher(line);
                if (m.find()) {
                    try {
                        progressCb.accept(Integer.parseInt(m.group(1)));
                    } catch (RuntimeException e) {
                        log.warn("Progress callback failed: {}", e.toString());
                    }
                }
            }
        } catch (IOException e) {
            // The stream closes when the process is killed: nothing more to read
            log.debug("whisper output closed: {}", e.getMessage());
        }
    }

    private static String tail(Deque<String> tail) {
        synchronized (tail) {
            return String.join("\n", tail);
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private List<String> buildCommand(Path audioFile, Path modelFile,
                                      String language, String outputBase) {
        List<String> cmd = new ArrayList<>();
        cmd.add(toolLocator.whisperCli().toString());
        cmd.add("-m"); cmd.add(modelFile.toAbsolutePath().toString());
        cmd.add("-f"); cmd.add(audioFile.toAbsolutePath().toString());
        cmd.add("-ojf");               // JSON output with word timestamps
        cmd.add("-bs"); cmd.add("1");  // greedy: beam size 1
        cmd.add("-bo"); cmd.add("1");  // greedy: best-of 1
        cmd.add("-l"); cmd.add(language);
        cmd.add("-of"); cmd.add(outputBase);
        cmd.add("-pp");                // print progress — parsed into PROGRESS messages
        return cmd;
    }

    private Path resolveModel(String modelName) throws IOException {
        // Try exact path first (user supplied full path)
        Path exact = Path.of(modelName);
        if (Files.exists(exact)) return exact;

        // Try models directory
        Path inModels = ModelDownloader.modelFile(modelsDir, modelName);
        if (Files.exists(inModels)) return inModels;

        // Also accept without the ggml- prefix
        Path plain = modelsDir.resolve(modelName + ".bin");
        if (Files.exists(plain)) return plain;

        throw new IOException(
                "Whisper model '" + modelName + "' not found in " + modelsDir +
                ". Download it first (see docs/DESIGN.md §8.5).");
    }
}
