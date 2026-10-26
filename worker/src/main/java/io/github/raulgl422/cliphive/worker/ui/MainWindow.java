package io.github.raulgl422.cliphive.worker.ui;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.config.WorkerPropertiesLoader;
import io.github.raulgl422.cliphive.worker.logging.UiLogAppender;
import io.github.raulgl422.cliphive.worker.runtime.WorkerController;
import io.github.raulgl422.cliphive.worker.runtime.WorkerState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Main window of the ClipHive worker UI (docs/DESIGN.md §8.2).
 *
 * <p>Displays: current state, active task, progress, recent log lines.
 * Buttons: Pause/Resume, Stop, Config (opens {@link ConfigEditor}).
 *
 * <p>Closing the window hides it to the tray (if tray is supported) or
 * minimizes it; it does NOT stop the worker.
 */
public class MainWindow {

    private static final int MAX_LOG_LINES = 200;

    private final WorkerController controller;
    private final WorkerProperties props;
    private final WorkerPropertiesLoader loader;
    private final Path configFile;

    // UI nodes (accessed only from FX thread)
    private Stage stage;
    private Label statusLabel;
    private Label taskLabel;
    private ProgressBar progressBar;
    private TextArea logArea;
    private Button pauseBtn;
    private Button stopBtn;

    /** Length of each line shown in {@link #logArea}, oldest first — to trim from the top. */
    private final Deque<Integer> logLineLengths = new ArrayDeque<>();

    public MainWindow(WorkerController controller,
                      WorkerProperties props,
                      WorkerPropertiesLoader loader,
                      Path configFile) {
        this.controller = controller;
        this.props = props;
        this.loader = loader;
        this.configFile = configFile;
    }

    /**
     * Builds and shows the primary stage. Call from the FX Application thread.
     * {@code minimized}: started with the session — stays in the tray (§8.3).
     */
    public void show(Stage primaryStage, boolean minimized) {
        this.stage = primaryStage;

        // ── Status row ──────────────────────────────────────────────────────
        statusLabel = new Label("Iniciando…");
        statusLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 1.1em;");

        taskLabel = new Label("—");
        taskLabel.setStyle("-fx-text-fill: #666;");

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        VBox topBox = new VBox(4, statusLabel, taskLabel, progressBar);
        topBox.setPadding(new Insets(12));
        topBox.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");

        // ── Log area ────────────────────────────────────────────────────────
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 0.85em;");

        // ── Buttons ─────────────────────────────────────────────────────────
        pauseBtn = new Button("Pausar");
        stopBtn  = new Button("Parar");
        Button configBtn = new Button("Configuración");

        pauseBtn.setOnAction(e -> controller.togglePause());
        stopBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "¿Parar el worker? Las tareas en curso serán liberadas.",
                    ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText(null);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) controller.stop();
            });
        });
        configBtn.setOnAction(e -> new ConfigEditor(props, loader, configFile).show(stage));

        HBox btnBar = new HBox(8, pauseBtn, stopBtn, new Separator(Orientation.VERTICAL), configBtn);
        btnBar.setPadding(new Insets(8, 12, 8, 12));
        btnBar.setStyle("-fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");

        // ── Layout ──────────────────────────────────────────────────────────
        VBox root = new VBox(topBox, logArea, btnBar);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        Scene scene = new Scene(root, 520, 380);
        primaryStage.setTitle("ClipHive Worker");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(400);
        primaryStage.setMinHeight(280);

        // Hide to tray on close (only if the icon is really there), otherwise minimize
        primaryStage.setOnCloseRequest(e -> {
            e.consume();
            if (TrayManager.isInstalled()) {
                primaryStage.hide();
            } else {
                primaryStage.setIconified(true);
            }
        });

        controller.addStateListener(this::onStateChanged);
        onStateChanged(controller.getState()); // initial sync

        // Current task + progress bar, and the worker's own log lines
        controller.addTaskListener(status -> updateTask(status.description(),
                status.percent() < 0 ? -1 : status.percent() / 100.0));
        UiLogAppender.attach(this::appendLog);

        if (!minimized) {
            primaryStage.show();
        } else if (!TrayManager.isSupported()) {
            // No tray to live in: show it minimized in the taskbar
            primaryStage.setIconified(true);
            primaryStage.show();
        }
    }

    /** Brings the window to front (called from tray left-click). */
    public void bringToFront() {
        if (stage != null) {
            stage.show();
            stage.toFront();
        }
    }

    /**
     * Appends a line to the log area (thread-safe — may be called from any thread).
     */
    public void appendLog(String line) {
        // Append and trim in place: re-setting the whole text re-lays out every line.
        // The length is measured, not line.length(): TextArea drops '\r' and other
        // control characters on insert.
        Platform.runLater(() -> {
            int before = logArea.getLength();
            logArea.appendText(line + "\n");
            logLineLengths.addLast(logArea.getLength() - before);
            while (logLineLengths.size() > MAX_LOG_LINES) {
                logArea.deleteText(0, logLineLengths.removeFirst());
            }
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    /**
     * Updates the current task display (thread-safe).
     *
     * @param description short description, or {@code null} to clear
     * @param progress    0.0–1.0, or negative to show indeterminate bar
     */
    private void updateTask(String description, double progress) {
        Platform.runLater(() -> {
            // While rejected, the task line shows what to fix (onStateChanged)
            WorkerState now = controller.getState();
            if (now == WorkerState.REJECTED || now == WorkerState.NOT_READY) return;
            taskLabel.setText(description != null ? description : "—");
            if (description != null) {
                progressBar.setVisible(true);
                progressBar.setProgress(progress < 0 ? ProgressBar.INDETERMINATE_PROGRESS : progress);
            } else {
                progressBar.setVisible(false);
            }
        });
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private void onStateChanged(WorkerState state) {
        Platform.runLater(() -> {
            statusLabel.setText(stateLabel(state));
            statusLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 1.1em; -fx-text-fill: " + stateColor(state));
            pauseBtn.setText(state == WorkerState.PAUSED ? "Reanudar" : "Pausar");
            pauseBtn.setDisable(state == WorkerState.STOPPING || state == WorkerState.STOPPED);
            stopBtn.setDisable(state == WorkerState.STOPPING || state == WorkerState.STOPPED);
            // What to fix goes where the current task is normally shown
            if (state == WorkerState.REJECTED || state == WorkerState.NOT_READY) {
                taskLabel.setText(controller.getProblem() != null ? controller.getProblem() : "—");
                progressBar.setVisible(false);
            } else if (state == WorkerState.IDLE) {
                taskLabel.setText("—");
            }

            if (state == WorkerState.STOPPED) {
                appendLog("[Sistema] Worker detenido.");
            }
        });
    }

    private static String stateLabel(WorkerState s) {
        return switch (s) {
            case STARTING   -> "Iniciando…";
            case CONNECTING -> "Conectando con el servidor…";
            case REJECTED   -> "El servidor rechaza la conexión";
            case NOT_READY  -> "No puede aceptar tareas";
            case IDLE       -> "En espera de tareas";
            case WORKING    -> "Procesando";
            case PAUSED     -> "Pausado (sin nuevas tareas)";
            case STOPPING   -> "Parando…";
            case STOPPED    -> "Detenido";
        };
    }

    private static String stateColor(WorkerState s) {
        return switch (s) {
            case IDLE, WORKING  -> "#2e7d32";   // green
            case PAUSED         -> "#e65100";   // orange
            case CONNECTING,
                 STARTING       -> "#1565c0";   // blue
            default             -> "#c62828";   // red
        };
    }
}
