package io.github.raulgl422.cliphive.worker.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerPropertiesLoaderTest {

    @TempDir Path tempDir;

    private final WorkerPropertiesLoader loader = new WorkerPropertiesLoader();

    @Test
    void defaults_used_when_no_yaml_file() {
        Path missing = tempDir.resolve("nonexistent.yml");
        WorkerProperties p = loader.load(missing);
        assertThat(p.getServerUrl()).isEqualTo("ws://localhost:8082/ws/worker");
        assertThat(p.getMaxConcurrentTasks()).isEqualTo(1);
        assertThat(p.getWhisperModel()).isEqualTo("auto");
    }

    @Test
    void yaml_values_override_defaults() throws IOException {
        Path yaml = tempDir.resolve("worker.yml");
        Files.writeString(yaml, """
                serverUrl: ws://100.1.2.3:8082/ws/worker
                workerToken: secret-token
                maxConcurrentTasks: 4
                whisperModel: medium
                """);

        WorkerProperties p = loader.load(yaml);

        assertThat(p.getServerUrl()).isEqualTo("ws://100.1.2.3:8082/ws/worker");
        assertThat(p.getWorkerToken()).isEqualTo("secret-token");
        assertThat(p.getMaxConcurrentTasks()).isEqualTo(4);
        assertThat(p.getWhisperModel()).isEqualTo("medium");
    }

    @Test
    void partial_yaml_leaves_other_fields_at_default() throws IOException {
        Path yaml = tempDir.resolve("worker.yml");
        Files.writeString(yaml, "whisperModel: large-v3-turbo\n");

        WorkerProperties p = loader.load(yaml);

        assertThat(p.getWhisperModel()).isEqualTo("large-v3-turbo");
        assertThat(p.getServerUrl()).isEqualTo("ws://localhost:8082/ws/worker");
    }

    @Test
    void empty_yaml_file_uses_defaults() throws IOException {
        Path yaml = tempDir.resolve("worker.yml");
        Files.writeString(yaml, "");

        WorkerProperties p = loader.load(yaml);

        assertThat(p.getServerUrl()).isEqualTo("ws://localhost:8082/ws/worker");
    }

    @Test
    void data_dir_is_os_specific() {
        WorkerProperties p = new WorkerProperties();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            assertThat(p.getDataDir()).containsIgnoringCase("cliphive");
        } else {
            assertThat(p.getDataDir()).contains("cliphive");
        }
    }

    @Test
    void a_blank_key_keeps_the_default_instead_of_the_text_null() throws IOException {
        Path yaml = tempDir.resolve("worker.yml");
        Files.writeString(yaml, "workerToken:\nffmpegPath:\nwhisperModel:\n");

        WorkerProperties p = loader.load(yaml);

        assertThat(p.getWorkerToken()).isEmpty();
        assertThat(p.getFfmpegPath()).isNotEqualTo("null");
        assertThat(p.getWhisperModel()).isEqualTo("auto");
    }

    @Test
    void broken_yaml_fails_naming_the_file() throws IOException {
        Path yaml = tempDir.resolve("worker.yml");
        Files.writeString(yaml, "serverUrl: [unclosed\n");

        assertThatThrownBy(() -> loader.load(yaml))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("worker.yml")
                .hasMessageContaining("YAML");
    }

    @Test
    void a_file_that_is_not_key_value_pairs_fails() throws IOException {
        Path yaml = tempDir.resolve("worker.yml");
        Files.writeString(yaml, "- just\n- a list\n");

        assertThatThrownBy(() -> loader.load(yaml)).isInstanceOf(ConfigException.class);
    }

    @Test
    void a_bad_number_fails_naming_the_key() throws IOException {
        Path yaml = tempDir.resolve("worker.yml");
        Files.writeString(yaml, "maxConcurrentTasks: abc\n");

        assertThatThrownBy(() -> loader.load(yaml))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("maxConcurrentTasks");
        Files.writeString(yaml, "maxConcurrentTasks: 0\n");
        assertThatThrownBy(() -> loader.load(yaml)).isInstanceOf(ConfigException.class);
    }

    @Test
    void save_then_load_round_trips_and_leaves_no_temp_file() throws IOException {
        Path yaml = tempDir.resolve("cfg").resolve("worker.yml");
        WorkerProperties p = new WorkerProperties();
        p.setWorkerToken("tok");
        p.setMaxConcurrentTasks(3);
        p.setForcedEncoder("nvenc");

        loader.save(p, yaml);
        loader.save(p, yaml); // replacing an existing file

        WorkerProperties back = loader.load(yaml);
        assertThat(back.getWorkerToken()).isEqualTo("tok");
        assertThat(back.getMaxConcurrentTasks()).isEqualTo(3);
        assertThat(back.getForcedEncoder()).isEqualTo("nvenc");
        assertThat(yaml.resolveSibling("worker.yml.tmp")).doesNotExist();
    }
}
