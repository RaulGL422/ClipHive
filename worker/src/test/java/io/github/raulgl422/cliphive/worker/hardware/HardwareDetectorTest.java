package io.github.raulgl422.cliphive.worker.hardware;

import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.tool.WorkerToolLocator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HardwareDetectorTest {

    private WorkerToolLocator locator() {
        WorkerProperties props = new WorkerProperties();
        return new WorkerToolLocator(props);
    }

    @Test
    void detect_returns_capabilities_without_throwing() {
        // In a CI environment ffmpeg/whisper-cli may not be present;
        // HardwareDetector must not throw — it logs warnings and uses defaults.
        HardwareDetector detector = new HardwareDetector(locator(), 2, List.of("medium"));
        WorkerCapabilities caps = detector.detect();

        assertThat(caps).isNotNull();
        assertThat(caps.maxConcurrentTasks()).isEqualTo(2);
        assertThat(caps.availableModels()).containsExactly("medium");
        assertThat(caps.encoders()).isNotEmpty();
        assertThat(caps.whisperBackend()).isNotNull();
    }

    @Test
    void detect_encoders_always_ends_with_a_software_encoder() {
        HardwareDetector detector = new HardwareDetector(locator(), 1, List.of());
        List<String> encoders = detector.detectEncoders();
        assertThat(encoders.getLast()).isIn("libopenh264", "libx264");
    }

    @Test
    void parse_encoder_names_skips_banner_and_legend() {
        List<String> output = List.of(
                "  configuration: --enable-gpl --enable-libx264",
                "Encoders:",
                " V..... = Video",
                " ------",
                " V....D libopenh264          OpenH264 H.264 / AVC",
                " V....D h264_amf             AMD AMF H.264 Encoder (codec h264)",
                " A....D aac                  AAC (Advanced Audio Coding)");

        assertThat(HardwareDetector.parseEncoderNames(output))
                .containsExactly("libopenh264", "h264_amf", "aac");
    }

    @Test
    void detect_whisper_backend_returns_known_value() {
        HardwareDetector detector = new HardwareDetector(locator(), 1, List.of());
        String backend = detector.detectWhisperBackend();
        assertThat(backend).isIn("cpu", "vulkan", "cuda");
    }

    @Test
    void gpu_available_false_when_backend_is_cpu() {
        HardwareDetector detector = new HardwareDetector(locator(), 1, List.of());
        // In CI there's no GPU, so backend defaults to "cpu" and gpuAvailable = false
        WorkerCapabilities caps = detector.detect();
        if ("cpu".equals(caps.whisperBackend())) {
            assertThat(caps.gpuAvailable()).isFalse();
        }
    }
}
