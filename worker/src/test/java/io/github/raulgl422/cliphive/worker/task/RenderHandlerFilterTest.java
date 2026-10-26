package io.github.raulgl422.cliphive.worker.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ffmpeg filter graph must use '.' as decimal separator whatever the
 * machine's locale: with a Spanish locale "%.3f" printed "5,000", and ffmpeg
 * read the comma as a filter separator ("No option name near '10:2'").
 */
class RenderHandlerFilterTest {

    private Locale original;

    @BeforeEach
    void spanishLocale() {
        original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("es-ES"));
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    void single_range_uses_dot_decimals() {
        String graph = RenderHandler.buildFilterComplex(List.<double[]>of(new double[]{5.0, 25.97}), 30);

        assertThat(graph)
                .contains("trim=5.000:25.970")
                .contains("atrim=5.000:25.970")
                .doesNotContain("5,000");
    }

    @Test
    void multi_range_uses_dot_decimals() {
        String graph = RenderHandler.buildFilterComplex(
                List.of(new double[]{1.5, 3.25}, new double[]{10.0, 12.125}), 60);

        assertThat(graph)
                .contains("trim=1.500:3.250")
                .contains("atrim=10.000:12.125")
                .doesNotContain("1,500");
    }

    @Test
    void encoder_filter_tail_goes_before_vout_inside_the_graph() {
        String graph = RenderHandler.buildFilterComplex(
                List.<double[]>of(new double[]{0.0, 3.0}), 30, "format=nv12,hwupload");
        assertThat(graph).contains("ass=clip.ass,format=nv12,hwupload[vout]");
        assertThat(RenderHandler.buildFilterComplex(List.<double[]>of(new double[]{0.0, 3.0}), 30))
                .contains("ass=clip.ass[vout]");
    }
}
