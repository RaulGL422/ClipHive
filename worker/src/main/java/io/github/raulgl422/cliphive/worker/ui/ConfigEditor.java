package io.github.raulgl422.cliphive.worker.ui;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.config.WorkerPropertiesLoader;
import io.github.raulgl422.cliphive.worker.net.ServerConnection;
import io.github.raulgl422.cliphive.worker.runtime.Autostart;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Modal config editor for the worker (docs/DESIGN.md §8.4).
 *
 * <p>Reads and writes {@code worker.yml} via {@link WorkerPropertiesLoader}.
 * The encoder applies from the next render; the other settings are read at
 * startup and take effect on restart.
 */
public class ConfigEditor {

    private static final Logger log = LoggerFactory.getLogger(ConfigEditor.class);

    private final WorkerProperties props;
    private final WorkerPropertiesLoader loader;
    private final Path configFile;

    public ConfigEditor(WorkerProperties props, WorkerPropertiesLoader loader, Path configFile) {
        this.props = props;
        this.loader = loader;
        this.configFile = configFile;
    }

    /**
     * Opens the modal editor. Blocks until closed.
     *
     * @return {@code true} when the settings were saved (and are now live)
     */
    public boolean show(Stage owner) {
        boolean[] saved = {false};
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle("Configuración — ClipHive Worker");
        stage.setResizable(false);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        // ── Fields ──────────────────────────────────────────────────────────
        TextField serverUrl = field(props.getServerUrl(), 320);
        PasswordField token  = new PasswordField();
        token.setText(props.getWorkerToken());
        token.setPrefWidth(320);

        TextField dataDir    = field(props.getDataDir(), 320);
        Spinner<Integer> maxTasks = new Spinner<>(1, 16, props.getMaxConcurrentTasks());
        maxTasks.setEditable(true);

        ComboBox<String> model = new ComboBox<>();
        model.getItems().addAll("auto", "base", "small", "medium", "large-v3-turbo");
        model.setValue(props.getWhisperModel());

        ComboBox<String> encoder = new ComboBox<>();
        encoder.getItems().addAll("auto", "amf", "nvenc", "vaapi", "software");
        encoder.setValue(props.getForcedEncoder());

        Autostart autostart = new Autostart();
        CheckBox startWithSystem = new CheckBox("Arrancar al iniciar sesión (minimizado en la bandeja)");
        startWithSystem.setSelected(props.isStartWithSystem());
        if (!autostart.isAvailable()) {
            startWithSystem.setDisable(true);
            startWithSystem.setText(startWithSystem.getText() + " — solo en la versión instalada");
        }

        int row = 0;
        addRow(grid, row++, "URL del servidor:", serverUrl);
        addRow(grid, row++, "Token del worker:", token);
        addRow(grid, row++, "Directorio de datos:", dataDir);
        addRow(grid, row++, "Tareas simultáneas:", maxTasks);
        addRow(grid, row++, "Modelo de Whisper:", model);
        addRow(grid, row++, "Encoder de vídeo:", encoder);
        // "Only on AC power" and a work schedule (DESIGN.md §8.3) aren't enforced yet,
        // so they aren't offered: a setting that does nothing would mislead.
        grid.add(startWithSystem, 1, row++);

        Label note = new Label("Nota: los cambios se aplican al reiniciar el worker, salvo el encoder, "
                + "que se usa ya en el siguiente render.");
        note.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #888;");
        grid.add(note, 0, row++, 2, 1);

        // ── Buttons ─────────────────────────────────────────────────────────
        Button save   = new Button("Guardar");
        Button cancel = new Button("Cancelar");
        save.setDefaultButton(true);
        cancel.setCancelButton(true);

        save.setOnAction(e -> {
            try {
                ServerConnection.serverUri(serverUrl.getText());
            } catch (IllegalArgumentException ex) {
                new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                return;
            }
            // Edit a copy and put it in place only once saved: a failed save
            // must not leave the running worker on settings that aren't on disk
            WorkerProperties draft = new WorkerProperties();
            draft.copyFrom(props);
            draft.setServerUrl(serverUrl.getText().trim());
            draft.setWorkerToken(token.getText().trim());
            draft.setDataDir(dataDir.getText().trim());
            draft.setMaxConcurrentTasks(maxTasks.getValue());
            draft.setWhisperModel(model.getValue());
            draft.setForcedEncoder(encoder.getValue());
            draft.setStartWithSystem(startWithSystem.isSelected());
            try {
                loader.save(draft, configFile);
            } catch (IOException ex) {
                log.error("Could not save config: {}", ex.getMessage());
                new Alert(Alert.AlertType.ERROR, "Error guardando la configuración:\n" + ex.getMessage())
                        .showAndWait();
                return;
            }
            props.copyFrom(draft);
            saved[0] = true;
            boolean startWith = draft.isStartWithSystem();
            Thread.ofVirtual().start(() -> {
                String problem = autostart.apply(startWith);
                if (problem != null) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                            "No se ha podido " + (startWith ? "activar" : "quitar")
                                    + " el arranque al iniciar sesión:\n" + problem).show());
                }
            });
            stage.close();
        });
        cancel.setOnAction(e -> stage.close());

        HBox buttons = new HBox(8, save, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8, 16, 16, 16));

        VBox root = new VBox(grid, buttons);
        stage.setScene(new Scene(root));
        stage.showAndWait();
        return saved[0];
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static TextField field(String value, double width) {
        TextField tf = new TextField(value);
        tf.setPrefWidth(width);
        return tf;
    }

    private static void addRow(GridPane grid, int row, String label, Control control) {
        grid.add(new Label(label), 0, row);
        grid.add(control, 1, row);
    }
}
