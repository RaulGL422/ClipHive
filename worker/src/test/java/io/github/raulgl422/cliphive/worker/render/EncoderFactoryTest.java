package io.github.raulgl422.cliphive.worker.render;

import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EncoderFactoryTest {

    @Test
    void amf_preferred_when_available() {
        var caps = new WorkerCapabilities(true, List.of("h264_amf", "libx264"), "vulkan",
                List.of("medium"), 2);
        assertThat(EncoderFactory.from(caps.encoders(), "auto", 30)).isInstanceOf(AmfEncoder.class);
    }

    @Test
    void nvenc_chosen_when_no_amf() {
        var caps = new WorkerCapabilities(true, List.of("h264_nvenc", "libx264"), "cuda",
                List.of("medium"), 2);
        assertThat(EncoderFactory.from(caps.encoders(), "auto", 30)).isInstanceOf(NvencEncoder.class);
    }

    @Test
    void vaapi_chosen_when_no_amf_or_nvenc() {
        var caps = new WorkerCapabilities(true, List.of("h264_vaapi", "libx264"), "vulkan",
                List.of("medium"), 2);
        assertThat(EncoderFactory.from(caps.encoders(), "auto", 30)).isInstanceOf(VaapiEncoder.class);
    }

    @Test
    void software_fallback_when_no_gpu_encoder() {
        var caps = new WorkerCapabilities(false, List.of("libx264"), "cpu",
                List.of("small"), 1);
        assertThat(EncoderFactory.from(caps.encoders(), "auto", 30)).isInstanceOf(SoftwareEncoder.class);
    }

    @Test
    void software_fallback_prefers_openh264() {
        var caps = new WorkerCapabilities(false, List.of("libopenh264"), "cpu",
                List.of("small"), 1);
        Encoder encoder = EncoderFactory.from(caps.encoders(), "auto", 30);
        assertThat(encoder.codecName()).isEqualTo("libopenh264");
        assertThat(encoder.preCodecArgs()).isEmpty();
    }

    @Test
    void software_fallback_uses_libx264_only_when_openh264_missing() {
        var caps = new WorkerCapabilities(false, List.of("libx264"), "cpu",
                List.of("small"), 1);
        Encoder encoder = EncoderFactory.from(caps.encoders(), "auto", 30);
        assertThat(encoder.codecName()).isEqualTo("libx264");
        assertThat(encoder.preCodecArgs()).containsExactly("-preset", "veryfast");
    }

    // ── forcedEncoder (DESIGN.md §8.4) ──────────────────────────────────────

    private static final List<String> AMF_AND_NVENC = List.of("h264_amf", "h264_nvenc", "libopenh264");

    @Test
    void forced_software_wins_over_a_working_gpu() {
        assertThat(EncoderFactory.from(AMF_AND_NVENC, "software", 30).codecName()).isEqualTo("libopenh264");
    }

    @Test
    void forced_gpu_that_passed_the_test_is_used() {
        assertThat(EncoderFactory.from(AMF_AND_NVENC, "nvenc", 30)).isInstanceOf(NvencEncoder.class);
        assertThat(EncoderFactory.from(AMF_AND_NVENC, " NVENC ", 30)).isInstanceOf(NvencEncoder.class);
    }

    @Test
    void forced_gpu_that_failed_the_test_falls_back_to_the_best_detected() {
        assertThat(EncoderFactory.from(AMF_AND_NVENC, "vaapi", 30)).isInstanceOf(AmfEncoder.class);
        assertThat(EncoderFactory.from(List.of("libopenh264"), "amf", 30)).isInstanceOf(SoftwareEncoder.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"auto", "AUTO", "quicksync"})
    void auto_blank_null_or_unknown_pick_the_best_detected(String forced) {
        assertThat(EncoderFactory.from(AMF_AND_NVENC, forced, 30)).isInstanceOf(AmfEncoder.class);
    }

    @Test
    void vaapi_uploads_inside_the_filter_graph_not_with_vf() {
        Encoder vaapi = EncoderFactory.from(List.of("h264_vaapi", "libopenh264"), "vaapi", 30);
        assertThat(vaapi.filterTail()).isEqualTo("format=nv12,hwupload");
        assertThat(vaapi.preCodecArgs()).doesNotContain("-vf");
    }

    @Test
    void bitrate_8mbps_at_30fps() {
        var caps = new WorkerCapabilities(false, List.of("libx264"), "cpu", List.of(), 1);
        assertThat(EncoderFactory.from(caps.encoders(), "auto", 30).bitrate()).isEqualTo(8_000_000);
    }

    @Test
    void bitrate_12mbps_at_60fps() {
        var caps = new WorkerCapabilities(false, List.of("libx264"), "cpu", List.of(), 1);
        assertThat(EncoderFactory.from(caps.encoders(), "auto", 60).bitrate()).isEqualTo(12_000_000);
    }
}
