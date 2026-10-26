package io.github.raulgl422.cliphive.worker.tool;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerToolLocatorTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    @TempDir
    Path tmp;

    private Path fakeExecutable(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(IS_WINDOWS ? name + ".exe" : name);
        Files.writeString(file, "");
        file.toFile().setExecutable(true);
        return file;
    }

    @Test
    void bundledBinaryIsFoundWhenNothingIsConfigured() throws IOException {
        Path bin = tmp.resolve("bin");
        Path whisper = fakeExecutable(bin, "whisper-cli-test-only");

        var locator = new WorkerToolLocator(new WorkerProperties(), bin);

        assertThat(locator.locate("whisper-cli-test-only", "")).isEqualTo(whisper);
    }

    @Test
    void configuredPathWinsOverBundledBinary() throws IOException {
        Path bin = tmp.resolve("bin");
        fakeExecutable(bin, "ffmpeg-test-only");
        Path configured = fakeExecutable(tmp.resolve("custom"), "ffmpeg");

        var locator = new WorkerToolLocator(new WorkerProperties(), bin);

        assertThat(locator.locate("ffmpeg-test-only", configured.toString())).isEqualTo(configured);
    }

    @Test
    void aBareCommandNameIsLookedUpOnPath() {
        var locator = new WorkerToolLocator(new WorkerProperties(), tmp.resolve("bin"));

        assertThatThrownBy(() -> locator.locate("ffmpeg", "no-such-command-cliphive-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not on PATH");
    }

    @Test
    void aConfiguredPathThatIsNotAnExecutableFailsSayingSo() {
        var locator = new WorkerToolLocator(new WorkerProperties(), tmp.resolve("bin"));
        String typo = tmp.resolve("custom").resolve("ffmepg.exe").toString();

        assertThatThrownBy(() -> locator.locate("ffmpeg", typo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an executable file")
                .hasMessageContaining("ffmepg");
    }
}
