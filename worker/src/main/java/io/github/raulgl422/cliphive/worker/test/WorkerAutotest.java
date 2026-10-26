package io.github.raulgl422.cliphive.worker.test;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.config.WorkerPropertiesLoader;
import io.github.raulgl422.cliphive.worker.model.ModelDownloader;
import io.github.raulgl422.cliphive.worker.render.Encoder;
import io.github.raulgl422.cliphive.worker.render.EncoderFactory;
import io.github.raulgl422.cliphive.worker.render.SoftwareEncoder;
import io.github.raulgl422.cliphive.worker.task.TaskIo;
import io.github.raulgl422.cliphive.worker.tool.ToolProcess;
import io.github.raulgl422.cliphive.worker.tool.WorkerToolLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot startup self-test run on first launch (docs/DESIGN.md §8.5).
 *
 * <p>The test:
 * <ol>
 *   <li>Generates a 3-second test tone WAV (16 kHz mono) with ffmpeg.
 *   <li>Transcribes it with whisper-cli using the chosen model.
 *   <li>Generates a 3-second solid-color test video with ffmpeg.
 *   <li>Re-encodes it with the encoder renders will use ({@link EncoderFactory}).
 * </ol>
 *
 * <p>If step 4 fails with a GPU encoder picked by {@code auto}, the software
 * encoder is tested next; if it passes, the method logs a warning, forces the
 * encoder to {@code "software"} in {@code props}, and persists the change via
 * {@link WorkerPropertiesLoader#save(WorkerProperties, Path)}. An encoder the user
 * forced is left alone: if it fails, the worker takes no tasks (NOT_READY) until
 * the user changes it. Whenever the worker ends up without a working encoder (a
 * forced one that fails, or the software encoder failing too), or the test is
 * interrupted, it isn't marked done and runs again next start.
 *
 * <p>A flag file {@code .autotest_done} in {@code dataDir} prevents re-running.
 * Delete it to force a re-test (e.g. after installing new GPU drivers), and set
 * {@code forcedEncoder} back to {@code auto} if the last one switched it to software.
 */
public class WorkerAutotest {

    private static final Logger log = LoggerFactory.getLogger(WorkerAutotest.class);
    private static final String FLAG_FILE = ".autotest_done";
    private static final int PROCESS_TIMEOUT_MINUTES = 10;

    private final WorkerProperties props;
    private final WorkerToolLocator toolLocator;
    private final WorkerPropertiesLoader loader;
    private final Path configFile;
    private final ProcessRunner processRunner;

    /** Runs one external command in {@code dir}; throws {@link IOException} when it fails. */
    @FunctionalInterface
    interface ProcessRunner {
        void run(List<String> cmd, Path dir) throws IOException, InterruptedException;
    }

    public WorkerAutotest(WorkerProperties props,
                          WorkerToolLocator toolLocator,
                          WorkerPropertiesLoader loader,
                          Path configFile) {
        this(props, toolLocator, loader, configFile, WorkerAutotest::runLogged);
    }

    /** Test seam: {@code processRunner} stands in for the real ffmpeg / whisper-cli runs. */
    WorkerAutotest(WorkerProperties props,
                   WorkerToolLocator toolLocator,
                   WorkerPropertiesLoader loader,
                   Path configFile,
                   ProcessRunner processRunner) {
        this.props = props;
        this.toolLocator = toolLocator;
        this.loader = loader;
        this.configFile = configFile;
        this.processRunner = processRunner;
    }

    /** @return {@code true} if the flag file is present (test already passed). */
    public boolean isDone() {
        return Files.exists(Path.of(props.getDataDir()).resolve(FLAG_FILE));
    }

    /**
     * Runs the autotest (see the class comment for what happens on failure).
     *
     * @param model    the Whisper model the worker resolved and downloaded
     * @param encoders usable encoders from {@code HardwareDetector}, best first
     * @return {@code true} when the worker can take tasks: a full pass, or a GPU
     *         encoder picked by {@code auto} that failed and was switched to a
     *         software encoder that passed (DESIGN.md §8.5)
     */
    public boolean run(String model, List<String> encoders) {
        log.info("Running worker autotest (first-run self-test)...");
        Path tmpDir = Path.of(props.getDataDir()).resolve("autotest_tmp");

        try {
            Files.createDirectories(tmpDir);

            // Step 1: generate a 3 s test tone
            Path testWav = tmpDir.resolve("test_audio.wav");
            runFfmpeg(toolLocator.ffmpeg(), tmpDir,
                    "-y", "-f", "lavfi",
                    "-i", "sine=frequency=440:duration=3",
                    "-ar", "16000", "-ac", "1",
                    testWav.toString());
            log.info("Autotest: generated test audio");

            // Step 2: transcribe
            Path whisperBin = toolLocator.whisperCli();
            Path modelFile = ModelDownloader.modelFile(
                    Path.of(props.getDataDir()).resolve("models"), model);

            runProcess(List.of(whisperBin.toString(),
                    "-m", modelFile.toString(),
                    "-f", testWav.toString(),
                    "-l", "es",
                    "--no-prints"), tmpDir);
            log.info("Autotest: transcription OK");

            // Step 3: generate 3s test video
            Path testVideo = tmpDir.resolve("test_video_src.mp4");
            runFfmpeg(toolLocator.ffmpeg(), tmpDir,
                    "-y", "-f", "lavfi",
                    "-i", "color=c=blue:size=128x128:rate=30",
                    "-t", "3",
                    // mpeg4 is ffmpeg's native encoder — always present,
                    // unlike libx264, which the LGPL build doesn't ship.
                    "-c:v", "mpeg4",
                    testVideo.toString());
            log.info("Autotest: generated test video");

            // Step 4: re-encode with the encoder renders will use
            String forced = props.getForcedEncoder();
            Encoder encoder = EncoderFactory.from(encoders, forced, 30);
            boolean encodeOk = tryEncode(testVideo, tmpDir, encoder);
            if (!encodeOk) {
                if (encoder instanceof SoftwareEncoder) {
                    log.error("Autotest: software encoder {} failed — renders will fail; "
                              + "check the ffmpeg build. The autotest runs again next start.",
                              encoder.codecName());
                    return false;
                }
                if (forced != null && !forced.isBlank() && !"auto".equalsIgnoreCase(forced.strip())) {
                    log.error("Autotest: the encoder you chose ({}) failed — keeping your choice, but this "
                              + "worker takes no tasks until it works: set forcedEncoder to auto or software. "
                              + "The autotest runs again next start.", forced);
                    return false;
                }
                // A GPU encoder picked by auto: switch to software only once that has passed too
                Encoder software = EncoderFactory.from(encoders, "software", 30);
                log.warn("Autotest: GPU encoder {} failed — testing the software encoder {}",
                         encoder.codecName(), software.codecName());
                if (!tryEncode(testVideo, tmpDir, software)) {
                    log.error("Autotest: software encoder {} failed too — renders will fail; "
                              + "check the ffmpeg build. The autotest runs again next start.",
                              software.codecName());
                    return false;
                }
                // EncoderFactory honours this on every render (DESIGN.md §8.4, §8.5)
                log.warn("Autotest: switching to the software encoder {}", software.codecName());
                props.setForcedEncoder("software");
                loader.save(props, configFile);
            }

            markDone();
            log.info("Autotest completed (first encoder ok={})", encodeOk);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Autotest interrupted — it runs again next start");
            return false;
        } catch (Exception e) {
            log.error("Autotest failed: {} — worker will continue anyway", e.getMessage(), e);
            return false;
        } finally {
            TaskIo.deleteRecursively(tmpDir);
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────

    /** Encodes the test video with the same {@link Encoder} pieces the render uses. */
    private boolean tryEncode(Path source, Path tmpDir, Encoder encoder) throws InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(toolLocator.ffmpeg().toString(), "-y", "-hide_banner"));
        cmd.addAll(encoder.preInputArgs());
        cmd.addAll(List.of("-i", source.toString()));
        if (!encoder.filterTail().isEmpty()) cmd.addAll(List.of("-vf", encoder.filterTail()));
        cmd.addAll(encoder.preCodecArgs());
        cmd.addAll(List.of("-c:v", encoder.codecName(), "-t", "3",
                tmpDir.resolve("test_encoded.mp4").toString()));
        try {
            runProcess(cmd, tmpDir);
            return true;
        } catch (IOException e) {
            log.warn("Autotest: encoder {} failed: {}", encoder.codecName(), e.getMessage());
            return false;
        }
    }

    private void runFfmpeg(Path ffmpeg, Path tmpDir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner"));
        cmd.addAll(List.of(args));
        runProcess(cmd, tmpDir);
    }

    private void runProcess(List<String> cmd, Path tmpDir) throws IOException, InterruptedException {
        processRunner.run(cmd, tmpDir);
    }

    /** Runs before the worker connects: a hung driver must not block startup forever. */
    private static void runLogged(List<String> cmd, Path tmpDir) throws IOException, InterruptedException {
        ToolProcess.runLogged(cmd, tmpDir, tmpDir.resolve("autotest.log"),
                Duration.ofMinutes(PROCESS_TIMEOUT_MINUTES), Path.of(cmd.getFirst()).getFileName().toString());
    }

    private void markDone() throws IOException {
        Path flag = Path.of(props.getDataDir()).resolve(FLAG_FILE);
        Files.createDirectories(flag.getParent());
        Files.writeString(flag, "done\n");
    }
}
