package io.github.raulgl422.cliphive.worker.ui;

import io.github.raulgl422.cliphive.worker.runtime.WorkerController;
import io.github.raulgl422.cliphive.worker.runtime.WorkerState;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * System tray icon manager for the worker UI (docs/DESIGN.md §8.2).
 *
 * <p>Uses {@code java.awt.SystemTray} for the tray icon and popup menu.
 * On desktops without tray support (e.g. GNOME without an extension),
 * {@link #isSupported()} returns {@code false} and this class is a no-op.
 *
 * <p>Must be initialized on the AWT event thread or before JavaFX starts
 * (call {@code System.setProperty("java.awt.headless","false")} early).
 */
public class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    private final WorkerController controller;
    private final Runnable openWindow;
    private TrayIcon trayIcon;
    private MenuItem pauseResume;

    public TrayManager(WorkerController controller, Runnable openWindow) {
        this.controller = controller;
        this.openWindow = openWindow;
    }

    public static boolean isSupported() {
        return SystemTray.isSupported();
    }

    /** The icon is really in the tray, so a hidden window can be reopened from it. */
    private static volatile boolean installed;

    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Installs the tray icon. Must be called on the AWT event thread.
     * Safe to call even when {@link #isSupported()} is {@code false} — it
     * becomes a no-op.
     */
    public void install() {
        if (!isSupported()) {
            log.info("System tray not supported on this desktop — tray icon disabled");
            return;
        }

        // A small colored dot, recolored per state
        Image icon = createIcon(Color.GRAY);
        PopupMenu popup = buildMenu();

        trayIcon = new TrayIcon(icon, "ClipHive Worker", popup);
        trayIcon.setImageAutoSize(true);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    Platform.runLater(openWindow);
                }
            }
        });

        try {
            SystemTray.getSystemTray().add(trayIcon);
            installed = true;
            log.info("Tray icon installed");
        } catch (AWTException | RuntimeException e) {
            // Without an icon a window started minimized (or hidden) could never be reopened
            log.warn("Could not install tray icon: {} — showing the window instead", e.getMessage());
            trayIcon = null;
            Platform.runLater(openWindow);
            return;
        }

        controller.addStateListener(this::onStateChanged);
    }

    public void remove() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private PopupMenu buildMenu() {
        PopupMenu menu = new PopupMenu();

        MenuItem open = new MenuItem("Abrir ventana");
        open.addActionListener(e -> Platform.runLater(openWindow));
        menu.add(open);

        menu.addSeparator();

        pauseResume = new MenuItem("Pausar");
        pauseResume.addActionListener(e -> controller.togglePause());
        menu.add(pauseResume);

        MenuItem stop = new MenuItem("Parar worker");
        stop.addActionListener(e -> controller.stop());
        menu.add(stop);

        menu.addSeparator();

        MenuItem exit = new MenuItem("Salir");
        exit.addActionListener(e -> {
            controller.stop();
            Platform.exit();
        });
        menu.add(exit);

        return menu;
    }

    private void onStateChanged(WorkerState state) {
        if (trayIcon == null) return;

        Color iconColor = switch (state) {
            case IDLE, WORKING -> Color.GREEN;
            case PAUSED        -> new Color(255, 165, 0); // orange
            case CONNECTING,
                 STARTING      -> Color.YELLOW;
            default            -> Color.RED;
        };

        trayIcon.setImage(createIcon(iconColor));
        String problem = state == WorkerState.REJECTED || state == WorkerState.NOT_READY
                ? controller.getProblem() : null;
        trayIcon.setToolTip("ClipHive Worker — " + stateLabel(state) + (problem != null ? ": " + problem : ""));

        EventQueue.invokeLater(() ->
                pauseResume.setLabel(state == WorkerState.PAUSED ? "Reanudar" : "Pausar"));
    }

    private static String stateLabel(WorkerState s) {
        return switch (s) {
            case STARTING   -> "Iniciando…";
            case CONNECTING -> "Conectando…";
            case REJECTED   -> "Rechazado por el servidor";
            case NOT_READY  -> "No puede trabajar";
            case IDLE       -> "En espera";
            case WORKING    -> "Procesando";
            case PAUSED     -> "Pausado";
            case STOPPING   -> "Parando…";
            case STOPPED    -> "Parado";
        };
    }

    private static Image createIcon(Color color) {
        int size = 16;
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(1, 1, size - 2, size - 2);
        g.dispose();
        return img;
    }
}
