package io.github.raulgl422.cliphive.worker.render;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FpsDeciderTest {

    @Test
    void explicit_60_returns_60() {
        assertThat(FpsDecider.decide("60", 30)).isEqualTo(60);
    }

    @Test
    void explicit_30_returns_30() {
        assertThat(FpsDecider.decide("30", 60)).isEqualTo(30);
    }

    @Test
    void auto_uses_source_fps_when_known() {
        assertThat(FpsDecider.decide("auto", 60)).isEqualTo(60);
        assertThat(FpsDecider.decide("auto", 30)).isEqualTo(30);
        assertThat(FpsDecider.decide("auto", 24)).isEqualTo(24);
    }

    @Test
    void auto_defaults_to_30_when_source_unknown() {
        assertThat(FpsDecider.decide("auto", 0)).isEqualTo(30);
        assertThat(FpsDecider.decide(null, 0)).isEqualTo(30);
        assertThat(FpsDecider.decide("", 0)).isEqualTo(30);
    }

    @Test
    void auto_clamps_to_30_if_source_exceeds_60() {
        // source fps > 60 is unusual; auto falls back to default
        assertThat(FpsDecider.decide("auto", 120)).isEqualTo(30);
    }
}
