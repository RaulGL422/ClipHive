package io.github.raulgl422.cliphive.worker;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.tool.WorkerToolLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerApplicationTest {

    @TempDir Path tempDir;

    @Test
    void scan_models_returns_empty_when_dir_missing() {
        Path missing = tempDir.resolve("models");
        assertThat(WorkerApplication.scanModels(missing)).isEmpty();
    }

    @Test
    void scan_models_strips_ggml_prefix_and_bin_suffix() throws IOException {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.createFile(modelsDir.resolve("ggml-small.bin"));
        Files.createFile(modelsDir.resolve("ggml-medium.bin"));
        Files.createFile(modelsDir.resolve("ggml-large-v3-turbo.bin"));

        List<String> models = WorkerApplication.scanModels(modelsDir);
        assertThat(models).containsExactlyInAnyOrder("small", "medium", "large-v3-turbo");
    }

    @Test
    void scan_models_ignores_non_bin_files() throws IOException {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.createFile(modelsDir.resolve("ggml-small.bin"));
        Files.createFile(modelsDir.resolve("README.md"));
        Files.createFile(modelsDir.resolve("checksum.sha256"));

        List<String> models = WorkerApplication.scanModels(modelsDir);
        assertThat(models).containsExactly("small");
    }

    @Test
    void unusable_reason_names_each_missing_tool() {
        WorkerProperties props = new WorkerProperties();
        props.setFfmpegPath(tempDir.resolve("missing-ffmpeg.exe").toString());
        props.setWhisperPath(tempDir.resolve("missing-whisper.exe").toString());

        String reason = WorkerApplication.unusableReason(new WorkerToolLocator(props), true);

        assertThat(reason).contains("ffmpeg").contains("whisper-cli");
    }

    @Test
    void unusable_reason_reports_a_failed_autotest_and_is_null_when_all_is_well() throws IOException {
        WorkerProperties props = new WorkerProperties();
        props.setFfmpegPath(fakeExecutable("ffmpeg").toString());
        props.setWhisperPath(fakeExecutable("whisper-cli").toString());
        WorkerToolLocator locator = new WorkerToolLocator(props);

        assertThat(WorkerApplication.unusableReason(locator, false)).contains("prueba de arranque");
        assertThat(WorkerApplication.unusableReason(locator, true)).isNull();
    }

    private Path fakeExecutable(String name) throws IOException {
        Path file = tempDir.resolve(name + ".exe");
        Files.writeString(file, "");
        file.toFile().setExecutable(true);
        return file;
    }
}
