package io.github.raulgl422.cliphive.worker.ui;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.config.WorkerPropertiesLoader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * First-run setup for the desktop worker: shown instead of the main window
 * when there is no worker token yet. Without it the worker would exit before
 * any window appeared — and the token is configured from a window.
 *
 * <p>After saving, the worker relaunches itself through the jpackage launcher
 * so the normal startup (hardware detection, model download, autotest) runs
 * with the new config.
 */
public class FirstRunSetupApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(FirstRunSetupApp.class);

    private static WorkerProperties injectedProps;
    private static WorkerPropertiesLoader injectedLoader;
    private static Path injectedConfigFile;

    /** Must be called before {@code Application.launch}. */
    public static void inject(WorkerProperties props, WorkerPropertiesLoader loader, Path configFile) {
        injectedProps      = props;
        injectedLoader     = loader;
        injectedConfigFile = configFile;
    }

    @Override
    public void start(Stage primaryStage) {
        boolean saved = new ConfigEditor(injectedProps, injectedLoader, injectedConfigFile).show(null);

        if (!saved || injectedProps.getWorkerToken().isBlank()) {
            log.info("Setup closed without saving a token — exiting");
            Platform.exit();
            return;
        }

        boolean relaunched = relaunch();
        new Alert(Alert.AlertType.INFORMATION, relaunched
                ? "Configuración guardada. ClipHive Worker se está iniciando.\n\n"
                  + "La primera vez descarga el modelo de Whisper y hace una prueba "
                  + "del equipo: puede tardar varios minutos en aparecer la ventana."
                : "Configuración guardada. Vuelve a abrir ClipHive Worker.").showAndWait();
        Platform.exit();
    }

    /** Starts a new worker process via the jpackage launcher, if there is one. */
    private static boolean relaunch() {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null || launcher.isBlank()) return false;
        try {
            new ProcessBuilder(launcher).start();
            return true;
        } catch (Exception e) {
            log.warn("Could not relaunch worker: {}", e.getMessage());
            return false;
        }
    }
}
