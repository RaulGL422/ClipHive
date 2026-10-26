package io.github.raulgl422.cliphive.worker.hardware;

import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import io.github.raulgl422.cliphive.worker.render.Encoder;
import io.github.raulgl422.cliphive.worker.render.EncoderFactory;
import io.github.raulgl422.cliphive.worker.tool.ToolProcess;
import io.github.raulgl422.cliphive.worker.tool.WorkerToolLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Detects available hardware capabilities at worker startup
 * (docs/DESIGN.md §8.5):
 * <ul>
 *   <li>H.264 encoders: compiled into ffmpeg ({@code ffmpeg -encoders}) and
 *       passing a test encode, best first
 *   <li>Whisper backend (Vulkan / CUDA / CPU) from the {@code whisper-cli}
 *       binary name, the installer's marker file or {@code --version}
 * </ul>
 *
 * <p>Results are packaged into a {@link WorkerCapabilities} record for the
 * {@code HELLO} message.
 */
public class HardwareDetector {

    private static final Logger log = LoggerFactory.getLogger(HardwareDetector.class);

    /** GPU encoders in order of preference — each one is test-encoded before use. */
    private static final List<String> GPU_H264_ENCODERS =
            List.of("h264_amf", "h264_nvenc", "h264_vaapi");

    /**
     * CPU fallbacks. The bundled ffmpeg is an LGPL build without libx264, so
     * libopenh264 comes first (docs/DESIGN.md §20, ffmpeg build decision).
     */
    private static final List<String> SOFTWARE_H264_ENCODERS =
            List.of("libopenh264", "libx264");

    private static final String DEFAULT_SOFTWARE_ENCODER = "libopenh264";

    private static final boolean IS_LINUX =
            System.getProperty("os.name", "").toLowerCase().contains("linux");

    private final WorkerToolLocator toolLocator;
    private final int maxConcurrentTasks;
    private final List<String> downloadedModels;

    public HardwareDetector(WorkerToolLocator toolLocator,
                            int maxConcurrentTasks,
                            List<String> downloadedModels) {
        this.toolLocator = toolLocator;
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.downloadedModels = downloadedModels;
    }

    /**
     * Runs detection and returns a populated {@link WorkerCapabilities}.
     * Never throws — if a probe fails it logs a warning and uses safe defaults.
     */
    public WorkerCapabilities detect() {
        List<String> encoders = detectEncoders();
        String whisperBackend = detectWhisperBackend();
        boolean gpuAvailable = !whisperBackend.equals("cpu");

        log.info("Hardware: encoders={}, whisperBackend={}, gpu={}",
                 encoders, whisperBackend, gpuAvailable);

        return new WorkerCapabilities(gpuAvailable, encoders, whisperBackend,
                                      downloadedModels, maxConcurrentTasks);
    }

    /**
     * Returns the H.264 encoders that actually work on this machine: the GPU
     * encoders that pass a short test encode (AMF → NVENC → VAAPI), followed
     * by one software encoder. Being compiled into ffmpeg isn't enough — the
     * LGPL build ships AMF and NVENC at once, but only the matching GPU can
     * run them.
     */
    List<String> detectEncoders() {
        Path ffmpeg;
        try {
            ffmpeg = toolLocator.ffmpeg();
        } catch (IllegalStateException e) {
            log.warn("ffmpeg not found — cannot detect encoders: {}", e.getMessage());
            return List.of(DEFAULT_SOFTWARE_ENCODER);
        }

        Set<String> compiled;
        try {
            Probe probe = runAndCapture(List.of(ffmpeg.toString(), "-hide_banner", "-encoders"), 30);
            if (probe.exitCode() != 0) {
                throw new IOException("exit code " + probe.exitCode() + ": " + probe.tail());
            }
            compiled = Set.copyOf(parseEncoderNames(probe.lines()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ffmpeg encoder probe failed: {} — defaulting to {}",
                     e.getMessage(), DEFAULT_SOFTWARE_ENCODER);
            return List.of(DEFAULT_SOFTWARE_ENCODER);
        }

        List<String> found = new ArrayList<>();
        for (String encoder : GPU_H264_ENCODERS) {
            if (compiled.contains(encoder) && testEncode(ffmpeg, encoder)) {
                found.add(encoder);
            }
        }
        found.add(SOFTWARE_H264_ENCODERS.stream()
                .filter(compiled::contains)
                .findFirst()
                .orElse(DEFAULT_SOFTWARE_ENCODER));
        return found;
    }

    /**
     * Extracts encoder names from {@code ffmpeg -encoders} output. Each encoder
     * line is {@code " V....D name   description"}; the header and legend
     * lines don't have a six-character flags column and are skipped.
     */
    static List<String> parseEncoderNames(List<String> lines) {
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+", 3);
            // Legend lines look like " V..... = Video" — same flags shape, but "=" as name.
            if (parts.length >= 2 && parts[0].matches("[VAS][.A-Z]{5}") && !parts[1].equals("=")) {
                names.add(parts[1]);
            }
        }
        return names;
    }

    /**
     * Encodes half a second of black frames with {@code encoder}, built from the
     * same {@link Encoder} pieces the render uses; true on success.
     */
    private boolean testEncode(Path ffmpeg, String encoder) {
        if (encoder.equals("h264_vaapi") && !IS_LINUX) return false;
        Encoder enc = EncoderFactory.from(List.of(encoder), "auto", 30);
        List<String> cmd = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-v", "error"));
        cmd.addAll(enc.preInputArgs());
        cmd.addAll(List.of("-f", "lavfi", "-i", "color=c=black:s=256x256:r=30", "-t", "0.5"));
        if (!enc.filterTail().isEmpty()) cmd.addAll(List.of("-vf", enc.filterTail()));
        cmd.addAll(enc.preCodecArgs());
        cmd.addAll(List.of("-c:v", enc.codecName(), "-f", "null", "-"));
        try {
            Probe probe = runAndCapture(cmd, 20);
            if (probe.exitCode() == 0) {
                log.info("Encoder {} test: OK", encoder);
                return true;
            }
            // ffmpeg's own words say why (no such device, driver too old…)
            log.info("Encoder {} test: not usable on this machine — {}", encoder, probe.tail());
            return false;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.info("Encoder {} test failed: {}", encoder, e.getMessage());
            return false;
        }
    }

    /** What a probe printed, and how it ended. */
    record Probe(int exitCode, List<String> lines) {
        /** The last few lines, for a log message. */
        String tail() {
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
        }
    }

    /**
     * Runs a short probe with its output in a temp file, not a pipe, so the
     * timeout can fire on a hung driver (reading a pipe would wait for the
     * process to end); the process is killed on timeout or interrupt.
     */
    private static Probe runAndCapture(List<String> cmd, int timeoutSeconds)
            throws IOException, InterruptedException {
        String name = Path.of(cmd.getFirst()).getFileName().toString();
        Path out = Files.createTempFile("cliphive-probe-", ".log");
        try {
            Process p = ToolProcess.start(new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(out.toFile()), name);
            try {
                if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw new IOException(name + " probe timed out after " + timeoutSeconds + " s");
                }
            } finally {
                ToolProcess.end(p, name);
            }
            List<String> lines = new String(Files.readAllBytes(out), StandardCharsets.UTF_8).lines().toList();
            return new Probe(p.exitValue(), lines);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /**
     * Detects the whisper.cpp backend by examining the {@code whisper-cli}
     * binary name, the installer's {@code whisper-cli.backend} marker, or its
     * version output. Returns {@code "vulkan"},
     * {@code "cuda"}, or {@code "cpu"}.
     */
    String detectWhisperBackend() {
        Path whisper;
        try {
            whisper = toolLocator.whisperCli();
        } catch (IllegalStateException e) {
            log.warn("whisper-cli not found: {}", e.getMessage());
            return "cpu";
        }

        // The binary name often encodes the backend: whisper-cli-vulkan, etc.
        String binaryName = whisper.getFileName().toString().toLowerCase();
        if (binaryName.contains("vulkan")) return "vulkan";
        if (binaryName.contains("cuda"))   return "cuda";

        // The installer writes the build variant next to the bundled binary
        // (whisper-cli.backend), since whisper-cli can't report it without a model.
        Path marker = whisper.resolveSibling("whisper-cli.backend");
        if (Files.isRegularFile(marker)) {
            try {
                String backend = Files.readString(marker).trim().toLowerCase();
                if (backend.equals("vulkan") || backend.equals("cuda")) return backend;
            } catch (IOException e) {
                log.debug("Could not read {}: {}", marker, e.getMessage());
            }
        }

        // Fall back to probing the version output
        try {
            // The exit code isn't checked: some builds print the backend and exit non-zero
            String output = String.join("\n",
                    runAndCapture(List.of(whisper.toString(), "--version"), 10).lines()).toLowerCase();
            if (output.contains("vulkan")) return "vulkan";
            if (output.contains("cuda"))   return "cuda";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("whisper-cli --version probe failed: {} — assuming the CPU backend", e.getMessage());
        }

        return "cpu";
    }
}
