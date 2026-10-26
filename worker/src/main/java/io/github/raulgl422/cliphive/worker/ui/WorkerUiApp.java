package io.github.raulgl422.cliphive.worker.ui;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.config.WorkerPropertiesLoader;
import io.github.raulgl422.cliphive.worker.runtime.Autostart;
import io.github.raulgl422.cliphive.worker.runtime.WorkerController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.EventQueue;
import java.nio.file.Path;

/**
 * JavaFX entry point for the worker UI (docs/DESIGN.md §8.2).
 *
 * <p>Launched by {@link io.github.raulgl422.cliphive.worker.WorkerApplication} when not
 * running in headless mode. The shared objects (controller, props, loader, config
 * file) are injected via static fields before {@link Application#launch} is called,
 * because JavaFX creates the Application instance itself.
 *
 * <p>Usage in WorkerApplication:
 * <pre>
 *   WorkerUiApp.inject(controller, props, loader, configFile);
 *   Application.launch(WorkerUiApp.class, args);
 * </pre>
 */
public class WorkerUiApp extends Application {

    // Injected before launch — JavaFX creates the instance, so statics are the bridge
    private static WorkerController injectedController;
    private static WorkerProperties injectedProps;
    private static WorkerPropertiesLoader injectedLoader;
    private static Path injectedConfigFile;

    private MainWindow mainWindow;
    private TrayManager trayManager;

    /** Must be called before {@code Application.launch}. */
    public static void inject(WorkerController controller,
                              WorkerProperties props,
                              WorkerPropertiesLoader loader,
                              Path configFile) {
        injectedController   = controller;
        injectedProps        = props;
        injectedLoader       = loader;
        injectedConfigFile   = configFile;
    }

    @Override
    public void init() {
        // Prevent JavaFX from exiting when the primary window is closed (hide to tray instead)
        Platform.setImplicitExit(false);
    }

    @Override
    public void start(Stage primaryStage) {
        mainWindow = new MainWindow(injectedController, injectedProps,
                                   injectedLoader, injectedConfigFile);

        trayManager = new TrayManager(injectedController, mainWindow::bringToFront);

        // The tray icon is AWT: it has to be installed on the AWT event thread
        EventQueue.invokeLater(trayManager::install);

        // Started with the session (§8.3): straight to the tray, without the window
        boolean minimized = getParameters().getRaw().contains(Autostart.MINIMIZED_FLAG);
        mainWindow.show(primaryStage, minimized);

        // Re-applied on every start, so the entry follows the launcher after an upgrade
        Thread.ofVirtual().start(() -> new Autostart().apply(injectedProps.isStartWithSystem()));
    }

    @Override
    public void stop() {
        if (trayManager != null) trayManager.remove();
        injectedController.stop();
    }
}
