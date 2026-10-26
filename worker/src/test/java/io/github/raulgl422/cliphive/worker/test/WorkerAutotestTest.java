package io.github.raulgl422.cliphive.worker.test;

import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.config.WorkerPropertiesLoader;
import io.github.raulgl422.cliphive.worker.tool.WorkerToolLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first-run autotest's encoder step (DESIGN.md §8.5), with the ffmpeg and
 * whisper-cli runs faked: only the encoders named in {@code failing} fail.
 */
class WorkerAutotestTest {

    private static final List<String> ENCODERS = List.of("h264_amf", "libopenh264");

    @TempDir Path dir;

    private WorkerProperties props;
    private Path configFile;
    private final List<String> encodedWith = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        props = new WorkerProperties();
        props.setDataDir(dir.resolve("data").toString());
        props.setFfmpegPath(fakeTool("ffmpeg").toString());
        props.setWhisperPath(fakeTool("whisper-cli").toString());
        configFile = dir.resolve("worker.yml");
    }

    private Path fakeTool(String name) throws IOException {
        Path tool = Files.createFile(dir.resolve(name + ".exe"));
        tool.toFile().setExecutable(true);
        return tool;
    }

    private boolean run(Set<String> failing) {
        WorkerAutotest.ProcessRunner runner = (cmd, workDir) -> {
            int codec = cmd.indexOf("-c:v");
            if (codec < 0) return;
            String name = cmd.get(codec + 1);
            if (!"mpeg4".equals(name)) encodedWith.add(name);  // mpeg4 makes the test video
            if (failing.contains(name)) throw new IOException(name + " failed");
        };
        return new WorkerAutotest(props, new WorkerToolLocator(props), new WorkerPropertiesLoader(),
                configFile, runner).run("base", ENCODERS);
    }

    private WorkerAutotest autotest() {
        return new WorkerAutotest(props, new WorkerToolLocator(props), new WorkerPropertiesLoader(), configFile);
    }

    @Test
    void a_full_pass_is_usable_and_marked_done() {
        assertThat(run(Set.of())).isTrue();
        assertThat(encodedWith).containsExactly("h264_amf");
        assertThat(autotest().isDone()).isTrue();
        assertThat(Files.exists(configFile)).isFalse();
    }

    @Test
    void a_gpu_picked_by_auto_that_fails_switches_to_a_software_encoder_that_passed() {
        props.setForcedEncoder("auto");

        assertThat(run(Set.of("h264_amf"))).isTrue();

        assertThat(encodedWith).containsExactly("h264_amf", "libopenh264");
        assertThat(props.getForcedEncoder()).isEqualTo("software");
        assertThat(new WorkerPropertiesLoader().load(configFile).getForcedEncoder()).isEqualTo("software");
        assertThat(autotest().isDone()).isTrue();
    }

    @Test
    void when_the_software_fallback_fails_too_the_worker_is_not_usable_and_retests_next_start() {
        assertThat(run(Set.of("h264_amf", "libopenh264"))).isFalse();

        assertThat(encodedWith).containsExactly("h264_amf", "libopenh264");
        assertThat(props.getForcedEncoder()).isNotEqualTo("software");
        assertThat(Files.exists(configFile)).isFalse();
        assertThat(autotest().isDone()).isFalse();
    }

    @Test
    void a_forced_encoder_that_fails_keeps_the_choice_is_not_usable_and_retests_next_start() {
        props.setForcedEncoder("amf");

        assertThat(run(Set.of("h264_amf"))).isFalse();

        assertThat(encodedWith).containsExactly("h264_amf");   // no silent switch to software
        assertThat(props.getForcedEncoder()).isEqualTo("amf");
        assertThat(Files.exists(configFile)).isFalse();
        assertThat(autotest().isDone()).isFalse();
    }

    @Test
    void a_failing_software_encoder_is_not_usable_and_retests_next_start() {
        props.setForcedEncoder("software");

        assertThat(run(Set.of("libopenh264"))).isFalse();

        assertThat(encodedWith).containsExactly("libopenh264");
        assertThat(autotest().isDone()).isFalse();
    }
}
