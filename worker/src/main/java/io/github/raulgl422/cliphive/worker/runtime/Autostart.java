package io.github.raulgl422.cliphive.worker.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts the worker with the user's session (docs/DESIGN.md §8.3), minimized
 * to the tray. Per user and without admin rights:
 * <ul>
 *   <li><b>Windows</b>: a value in {@code HKCU\...\CurrentVersion\Run}, written
 *       with {@code reg.exe} (argument list, never through {@code cmd}).
 *   <li><b>Linux</b>: an XDG autostart entry, {@code ~/.config/autostart/cliphive-worker.desktop}.
 * </ul>
 * Only the installed build has a launcher to point at ({@code jpackage.app-path});
 * run with {@code java -jar}, nothing is touched. Never throws: a failure is
 * logged and the worker carries on.
 */
public class Autostart {

    private static final Logger log = LoggerFactory.getLogger(Autostart.class);

    /** Passed by the login entry: open in the tray instead of showing the window. */
    public static final String MINIMIZED_FLAG = "--minimized";

    static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static final String VALUE_NAME = "ClipHive Worker";
    static final String DESKTOP_FILE = "cliphive-worker.desktop";

    private final String osName;
    private final String launcher;
    private final Path autostartDir;
    private final String runKey;

    /** For the running worker: this OS, the jpackage launcher and the user's autostart folder. */
    public Autostart() {
        this(System.getProperty("os.name", ""), System.getProperty("jpackage.app-path"),
                linuxAutostartDir(), RUN_KEY);
    }

    /** Everything replaceable, for tests. */
    Autostart(String osName, String launcher, Path autostartDir, String runKey) {
        this.osName = osName.toLowerCase();
        this.launcher = launcher == null || launcher.isBlank() ? null : launcher;
        this.autostartDir = autostartDir;
        this.runKey = runKey;
    }

    /** True when there's an installed launcher and an OS this knows how to configure. */
    public boolean isAvailable() {
        return launcher != null && (isWindows() || isLinux());
    }

    /**
     * Makes the login entry match {@code enabled}: written (again, so it follows
     * the launcher after an upgrade) or removed.
     *
     * @return {@code null} when done (or when there's nothing to do here), else
     *         what went wrong, for the config editor to show
     */
    public String apply(boolean enabled) {
        if (!isAvailable()) {
            log.debug("Autostart not available here (launcher={}, os={})", launcher, osName);
            return null;
        }
        try {
            if (isWindows()) applyWindows(enabled);
            else applyLinux(enabled);
            log.info("Start with the session: {}", enabled ? "on" : "off");
            return null;
        } catch (IOException e) {
            log.warn("Could not {} start with the session: {}", enabled ? "set up" : "remove", e.getMessage());
            return e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while {} start with the session", enabled ? "setting up" : "removing");
            return "interrumpido";
        }
    }

    // ── Windows ──────────────────────────────────────────────────────────────

    private void applyWindows(boolean enabled) throws IOException, InterruptedException {
        if (enabled) {
            run(List.of("reg", "add", runKey, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", regData(launcher), "/f"), false);
        } else {
            // Missing value = already off: reg exits 1, that's fine
            run(List.of("reg", "delete", runKey, "/v", VALUE_NAME, "/f"), true);
        }
    }

    /**
     * The {@code /d} argument for {@code reg add}: the value is
     * {@code "C:\path with spaces\ClipHive Worker.exe" --minimized}, and reg.exe
     * reads its command line with the C runtime rules, so the inner quotes go as
     * {@code \"}. The whole argument is already quoted, so Java passes it as is.
     */
    static String regData(String launcher) {
        return "\"\\\"" + launcher + "\\\" " + MINIMIZED_FLAG + "\"";
    }

    private static void run(List<String> command, boolean failureIsFine) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(command.getFirst() + " did not finish");
        }
        if (p.exitValue() != 0 && !failureIsFine) {
            throw new IOException(command.getFirst() + " exited with " + p.exitValue() + ": " + out);
        }
    }

    // ── Linux ────────────────────────────────────────────────────────────────

    private void applyLinux(boolean enabled) throws IOException {
        Path file = autostartDir.resolve(DESKTOP_FILE);
        if (enabled) {
            Files.createDirectories(autostartDir);
            Files.writeString(file, desktopEntry(launcher), StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(file);
        }
    }

    /** XDG desktop entry; {@code Exec} quoted per the spec so a path with spaces works. */
    static String desktopEntry(String launcher) {
        String escaped = launcher.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("`", "\\`").replace("$", "\\$");
        return """
                [Desktop Entry]
                Type=Application
                Name=ClipHive Worker
                Comment=Transcribe y renderiza clips para ClipHive
                Exec="%s" %s
                Terminal=false
                X-GNOME-Autostart-enabled=true
                """.formatted(escaped, MINIMIZED_FLAG);
    }

    private static Path linuxAutostartDir() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return config.resolve("autostart");
    }

    private boolean isWindows() {
        return osName.contains("windows");
    }

    private boolean isLinux() {
        return osName.contains("linux");
    }
}
