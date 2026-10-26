package io.github.raulgl422.cliphive.worker.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AutostartTest {

    /** A throwaway key, never the real Run key. */
    private static final String TEST_KEY = "HKCU\\Software\\ClipHiveAutostartTest";

    @TempDir Path dir;

    @AfterEach
    void removeTestKey() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            new ProcessBuilder("reg", "delete", TEST_KEY, "/f").redirectErrorStream(true).start().waitFor();
        }
    }

    @Test
    void linux_gets_an_xdg_autostart_entry_and_loses_it_when_turned_off() throws Exception {
        var autostart = new Autostart("Linux", "/opt/cliphive worker/bin/ClipHive Worker", dir.resolve("autostart"), TEST_KEY);
        assertThat(autostart.isAvailable()).isTrue();

        autostart.apply(true);
        Path entry = dir.resolve("autostart").resolve(Autostart.DESKTOP_FILE);
        assertThat(Files.readString(entry)).contains("Type=Application")
                .contains("Exec=\"/opt/cliphive worker/bin/ClipHive Worker\" --minimized");

        autostart.apply(false);
        assertThat(entry).doesNotExist();
        autostart.apply(false); // already off: nothing to do, no error
    }

    @Test
    void without_an_installed_launcher_nothing_is_touched() {
        var autostart = new Autostart("Linux", null, dir.resolve("autostart"), TEST_KEY);
        assertThat(autostart.isAvailable()).isFalse();
        autostart.apply(true);
        assertThat(dir.resolve("autostart")).doesNotExist();
    }

    @Test
    void desktop_entry_escapes_what_the_spec_says() {
        assertThat(Autostart.desktopEntry("/home/a$b/\"x\"")).contains("Exec=\"/home/a\\$b/\\\"x\\\"\" --minimized");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windows_writes_the_quoted_launcher_to_the_registry_and_removes_it() throws Exception {
        String exe = "C:\\Program Files\\ClipHive Worker\\ClipHive Worker.exe";
        var autostart = new Autostart("Windows 11", exe, dir, TEST_KEY);

        autostart.apply(true);
        assertThat(query()).contains("\"" + exe + "\" --minimized");

        autostart.apply(false);
        assertThat(query()).doesNotContain(Autostart.VALUE_NAME);
        autostart.apply(false); // already off: no error
    }

    private static String query() throws Exception {
        Process p = new ProcessBuilder("reg", "query", TEST_KEY).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }
}
