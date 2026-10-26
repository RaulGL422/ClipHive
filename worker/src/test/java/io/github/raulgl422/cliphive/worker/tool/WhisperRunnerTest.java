package io.github.raulgl422.cliphive.worker.tool;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhisperRunnerTest {

    @TempDir Path tempDir;

    private WhisperRunner runner() {
        WorkerProperties props = new WorkerProperties();
        WorkerToolLocator locator = new WorkerToolLocator(props);
        return new WhisperRunner(locator, tempDir.resolve("models"));
    }

    @Test
    void run_throws_when_model_not_found() {
        WhisperRunner r = runner();
        Path fakeAudio = tempDir.resolve("audio.wav");
        Path out = tempDir.resolve("out.json");

        assertThatThrownBy(() -> r.run(fakeAudio, out, "es", "nonexistent-model", pct -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void run_throws_when_whisper_cli_not_on_path_or_model_missing() throws IOException {
        // Create a fake model file so resolveModel passes, but whisper-cli isn't available
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.createFile(modelsDir.resolve("ggml-small.bin"));

        WhisperRunner r = runner();
        Path fakeAudio = tempDir.resolve("audio.wav");
        Files.createFile(fakeAudio);
        Path out = tempDir.resolve("out.json");

        // whisper-cli is not installed in CI — should throw IOException or IllegalStateException
        assertThatThrownBy(() -> r.run(fakeAudio, out, "es", "small", pct -> {}))
                .isInstanceOfAny(IOException.class, IllegalStateException.class);
    }

    @Test
    void progress_pattern_matches_whisper_output() {
        // Unit-test the regex without running the process
        Pattern pattern = Pattern.compile("progress\\s*=\\s*(\\d+)%", Pattern.CASE_INSENSITIVE);

        List<Integer> captured = new ArrayList<>();
        for (String line : List.of(
                "whisper_full_with_state: progress = 0%",
                "whisper_full_with_state: progress = 42%",
                "whisper_full_with_state: progress = 100%")) {
            Matcher m = pattern.matcher(line);
            if (m.find()) captured.add(Integer.parseInt(m.group(1)));
        }

        assertThat(captured).containsExactly(0, 42, 100);
    }
}
