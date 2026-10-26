package io.github.raulgl422.cliphive.worker;

import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.config.WorkerPropertiesLoader;
import io.github.raulgl422.cliphive.worker.hardware.HardwareDetector;
import io.github.raulgl422.cliphive.worker.logging.LogDirDefiner;
import io.github.raulgl422.cliphive.worker.model.ModelDownloader;
import io.github.raulgl422.cliphive.worker.net.ServerConnection;
import io.github.raulgl422.cliphive.worker.runtime.WorkerController;
import io.github.raulgl422.cliphive.worker.runtime.WorkerState;
import io.github.raulgl422.cliphive.worker.task.RenderHandler;
import io.github.raulgl422.cliphive.worker.task.TaskOrchestrator;
import io.github.raulgl422.cliphive.worker.task.TranscribeHandler;
import io.github.raulgl422.cliphive.worker.test.WorkerAutotest;
import io.github.raulgl422.cliphive.worker.tool.WhisperRunner;
import io.github.raulgl422.cliphive.worker.tool.WorkerToolLocator;
import io.github.raulgl422.cliphive.worker.ui.FirstRunSetupApp;
import io.github.raulgl422.cliphive.worker.ui.WorkerUiApp;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Worker entry point (docs/DESIGN.md §8.2).
 *
 * <p>Usage:
 * <pre>
 *   java -jar worker.jar               # desktop mode (JavaFX window + tray)
 *   java -jar worker.jar --headless    # no UI — for Linux services or CI
 * </pre>
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Load config from {@code worker.yml} + env overrides.
 *   <li>Detect hardware (encoders, whisper backend).
 *   <li>Download the whisper model if not present.
 *   <li>Run the one-shot autotest on first launch.
 *   <li>Assemble the dependency graph.
 *   <li>Register shutdown hook for clean TASK_RELEASE + GOODBYE.
 *   <li>In headless mode: connect and block.
 *      In UI mode: launch JavaFX (it connects after the window opens).
 * </ol>
 */
public class WorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(WorkerApplication.class);

    public static void main(String[] args) {
        boolean headless = Arrays.asList(args).contains("--headless");
        // The launcher of the installed app discards stderr: without this an
        // exception on any background thread would leave no trace in the log.
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                log.error("Uncaught exception on thread {}", t.getName(), e));
        try {
            start(args, headless);
        } catch (Throwable e) {
            // Startup runs before any window exists: without this dialog a failure
            // here would end the process with nothing on screen.
            log.error("ClipHive Worker could not start: {}", e.getMessage(), e);
            if (!headless && !GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null,
                        "El worker no ha podido arrancar:\n\n" + e.getMessage()
                                + "\n\nMás detalles en el log: " + new LogDirDefiner().getPropertyValue(),
                        "ClipHive Worker", JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
        }
    }

    private static void start(String[] args, boolean headless) throws Exception {
        log.info("ClipHive Worker starting (headless={})", headless);

        // Needed so AWT system tray works alongside JavaFX
        if (!headless) {
            System.setProperty("java.awt.headless", "false");
        }

        // 1. Load configuration
        WorkerPropertiesLoader loader = new WorkerPropertiesLoader();
        Path configFile = configFilePath();
        WorkerProperties props = loader.load(configFile);

        if (props.getWorkerToken().isBlank()) {
            if (!headless) {
                // Desktop first run: the token is entered in the UI, so open the
                // setup window instead of exiting before anything is shown.
                log.info("No worker token yet — opening first-run setup");
                FirstRunSetupApp.inject(props, loader, configFile);
                Application.launch(FirstRunSetupApp.class, args);
                System.exit(0);
            }
            log.error("CLIPHIVE_WORKER_TOKEN is not set. " +
                      "Set it in worker.yml or the CLIPHIVE_WORKER_TOKEN env var.");
            System.exit(1);
        }

        // 2. Detect hardware
        WorkerToolLocator toolLocator = new WorkerToolLocator(props);
        Path modelsDir = Path.of(props.getDataDir()).resolve("models");
        List<String> downloadedModels = scanModels(modelsDir);

        HardwareDetector detector = new HardwareDetector(
                toolLocator, props.getMaxConcurrentTasks(), downloadedModels);
        WorkerCapabilities caps = detector.detect();

        // 3. Download model if not present
        String targetModel = resolveModel(props, caps);
        if (!ModelDownloader.isPresent(targetModel, modelsDir)) {
            log.info("Whisper model '{}' not found — downloading...", targetModel);
            try {
                long[] nextLog = {0};
                new ModelDownloader().ensureModel(targetModel, modelsDir, bytes -> {
                    // Called per 64 KB chunk — log every 100 MB instead.
                    if (bytes >= nextLog[0]) {
                        log.info("Model download: {} MB", bytes / 1_000_000);
                        nextLog[0] += 100_000_000;
                    }
                });
            } catch (IOException e) {
                // A failed download (no network, bad hash) must not kill the
                // worker if an already-downloaded model can stand in for it.
                String fallback = bestDownloadedModel(modelsDir);
                if (fallback == null) throw e;
                log.warn("Could not download model '{}' ({}) — using '{}' instead",
                         targetModel, e.getMessage(), fallback);
                targetModel = fallback;
            }
        }

        // 4. Autotest on first launch
        WorkerAutotest autotest = new WorkerAutotest(props, toolLocator, loader, configFile);
        boolean autotestOk = autotest.isDone() || autotest.run(targetModel, caps.encoders());

        // A worker that can't do the work gets no tasks instead of failing them (DESIGN.md §8.5)
        String unusable = unusableReason(toolLocator, autotestOk);
        if (unusable != null) {
            log.error("This worker can't take tasks: {}", unusable);
            if (headless) {
                System.exit(2);
            }
            caps = new WorkerCapabilities(caps.gpuAvailable(), caps.encoders(), caps.whisperBackend(),
                                          caps.availableModels(), 0);
        }

        // 5. Assemble components
        WorkerController controller = new WorkerController();
        controller.setUnusable(unusable);

        WhisperRunner whisperRunner = new WhisperRunner(toolLocator, modelsDir);
        TranscribeHandler transcribeHandler = new TranscribeHandler(props, whisperRunner, targetModel);
        RenderHandler renderHandler = new RenderHandler(props, toolLocator, caps);
        TaskOrchestrator orchestrator = new TaskOrchestrator(transcribeHandler, renderHandler, controller);
        ServerConnection conn = new ServerConnection(props, caps, orchestrator);
        conn.setOutgoingListener(orchestrator::onOutgoing);

        controller.wire(conn, orchestrator);

        // 6. Shutdown hook — sends TASK_RELEASE + GOODBYE on SIGTERM / Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            controller.stop();
        }, "worker-shutdown"));

        // 7. Run
        if (headless) {
            runHeadless(controller, conn);
        } else {
            runWithUi(controller, props, loader, configFile, conn, args);
        }
    }

    // ── Headless mode ────────────────────────────────────────────────────────

    private static void runHeadless(WorkerController controller, ServerConnection conn) {
        // Retries until connected (and after every drop) — a server that's
        // down or a Tailscale link not up yet isn't fatal.
        conn.runWithReconnect(controller::onConnecting, () -> {
            controller.onConnected();
            log.info("Worker ready (headless) — waiting for tasks");
        }, controller::onRejected);
    }

    // ── UI mode ──────────────────────────────────────────────────────────────

    private static void runWithUi(WorkerController controller,
                                   WorkerProperties props,
                                   WorkerPropertiesLoader loader,
                                   Path configFile,
                                   ServerConnection conn,
                                   String[] args) throws Exception {
        // Connect in a background thread so the UI starts immediately
        // and keep retrying while the server is unreachable (window shows "connecting",
        // or "rejected" with what to fix).
        Thread connectThread = Thread.ofVirtual().name("worker-connect").start(() ->
                conn.runWithReconnect(controller::onConnecting, () -> {
                    controller.onConnected();
                    log.info("Worker ready (UI mode) — waiting for tasks");
                }, controller::onRejected));

        // Listen for stop to interrupt the connect thread
        controller.addStateListener(state -> {
            if (state == WorkerState.STOPPED) connectThread.interrupt();
        });

        // Launch JavaFX (blocks until the window is closed and Platform.exit() is called)
        WorkerUiApp.inject(controller, props, loader, configFile);
        Application.launch(WorkerUiApp.class, args);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** What stops this worker from doing tasks, for the window, or null when it can. */
    static String unusableReason(WorkerToolLocator toolLocator, boolean autotestOk) {
        List<String> problems = new ArrayList<>();
        try {
            toolLocator.ffmpeg();
        } catch (IllegalStateException e) {
            problems.add("no se encuentra ffmpeg (" + e.getMessage() + ")");
        }
        try {
            toolLocator.whisperCli();
        } catch (IllegalStateException e) {
            problems.add("no se encuentra whisper-cli (" + e.getMessage() + ")");
        }
        if (problems.isEmpty() && !autotestOk) {
            problems.add("la prueba de arranque ha fallado; mira el log. Se repite al volver a abrir el worker");
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    private static Path configFilePath() {
        WorkerProperties defaults = new WorkerProperties();
        return Path.of(defaults.getDataDir()).resolve("worker.yml");
    }

    /** Largest model already downloaded (best quality first), or null if none. */
    static String bestDownloadedModel(Path modelsDir) {
        List<String> present = scanModels(modelsDir);
        for (String model : List.of("large-v3-turbo", "medium", "small", "base")) {
            if (present.contains(model)) return model;
        }
        return null;
    }

    /** Returns names of ggml model files already present in {@code modelsDir}. */
    static List<String> scanModels(Path modelsDir) {
        if (!Files.exists(modelsDir)) return List.of();
        try (var stream = Files.list(modelsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        if (name.startsWith("ggml-")) name = name.substring(5);
                        return name.substring(0, name.length() - 4);
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("Could not scan models dir {}: {}", modelsDir, e.getMessage());
            return List.of();
        }
    }

    /**
     * Picks the target model: explicit config > hardware-aware default (docs/DESIGN.md §9.1).
     * large-v3-turbo / medium with GPU; small / base on CPU.
     */
    private static String resolveModel(WorkerProperties props, WorkerCapabilities caps) {
        if (!"auto".equalsIgnoreCase(props.getWhisperModel())) {
            return props.getWhisperModel();
        }
        if (caps.gpuAvailable()) {
            return "large-v3-turbo";
        }
        return "base";
    }
}
