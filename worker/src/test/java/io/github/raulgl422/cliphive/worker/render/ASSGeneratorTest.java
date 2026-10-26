package io.github.raulgl422.cliphive.worker.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ASSGeneratorTest {

    @TempDir Path tempDir;

    private final ASSGenerator gen = new ASSGenerator();

    private static Map<String, Object> word(String text, long startMs, long endMs) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("text", text);
        w.put("startMs", startMs);
        w.put("endMs", endMs);
        return w;
    }

    // ── assTime ───────────────────────────────────────────────────────────────

    @Test
    void assTime_formats_zero() {
        assertThat(ASSGenerator.assTime(0)).isEqualTo("0:00:00.00");
    }

    @Test
    void assTime_formats_1_hour_30_min() {
        // 1h30m = 5400 s = 5400000 ms
        assertThat(ASSGenerator.assTime(5_400_000L)).isEqualTo("1:30:00.00");
    }

    @Test
    void assTime_formats_centiseconds() {
        // 1500 ms = 1.5 s = 0:00:01.50
        assertThat(ASSGenerator.assTime(1_500L)).isEqualTo("0:00:01.50");
    }

    // ── groupWords ────────────────────────────────────────────────────────────

    @Test
    void short_sentence_produces_one_group() {
        var words = List.of(
                word("hola", 0, 500),
                word("mundo", 600, 1200)
        );
        var groups = gen.groupWords(words);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).words()).hasSize(2);
    }

    @Test
    void six_words_split_into_two_groups_of_max_five() {
        List<Map<String, Object>> words = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            words.add(word("w" + i, i * 1000L, i * 1000L + 800));
        }
        var groups = gen.groupWords(words);
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).words().size()).isLessThanOrEqualTo(ASSGenerator.MAX_WORDS_PER_GROUP);
    }

    @Test
    void group_split_at_comma() {
        var words = List.of(
                word("hello,", 0, 500),
                word("world", 600, 1200),
                word("how", 1300, 1800),
                word("are", 1900, 2400)
        );
        var groups = gen.groupWords(words);
        // Should cut after "hello," since it ends with punctuation
        assertThat(groups.get(0).words()).anyMatch(w -> w.get("text").toString().endsWith(","));
    }

    @Test
    void group_duration_at_least_minimum() {
        // A single very short word
        var words = List.of(word("a", 0, 100));
        var groups = gen.groupWords(words);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).endMs() - groups.get(0).startMs())
                .isGreaterThanOrEqualTo(ASSGenerator.MIN_GROUP_DURATION_MS);
    }

    // ── buildDialogue ─────────────────────────────────────────────────────────

    @Test
    void dialogue_line_contains_kf_tags() {
        var group = new ASSGenerator.WordGroup(0, 1500,
                List.of(word("hello", 0, 700), word("world", 800, 1500)));
        String line = gen.buildDialogue(group);
        assertThat(line).startsWith("Dialogue:");
        assertThat(line).contains("{\\kf");
        assertThat(line).contains("hello");
        assertThat(line).contains("world");
    }

    // ── generate ──────────────────────────────────────────────────────────────

    @Test
    void the_on_screen_title_sits_on_top_for_the_whole_clip() throws IOException {
        Path dest = tempDir.resolve("titled.ass");
        gen.generate(List.of(word("hola", 0, 500)), 0, dest, ASSGenerator.DEFAULT_MARGIN_V,
                "La remontada · 2/3 {\\b1}");

        String content = Files.readString(dest);
        assertThat(content).contains("Style: Title,").contains(",3,16,0,8,90,90,270,1");
        assertThat(content).contains("Dialogue: 1,0:00:00.00,9:59:59.99,Title,,0,0,0,,La remontada · 2/3 (/b1)");

        gen.generate(List.of(word("hola", 0, 500)), 0, dest, ASSGenerator.DEFAULT_MARGIN_V, " ");
        assertThat(Files.readString(dest)).doesNotContain(",Title,,");
    }

    @Test
    void subtitle_height_comes_from_the_server_and_defaults_to_a_quarter_up() {
        assertThat(ASSGenerator.header(ASSGenerator.DEFAULT_MARGIN_V)).contains(",2,60,60,480,1");
        assertThat(ASSGenerator.header(700)).contains(",2,60,60,700,1");
        assertThat(ASSGenerator.header(5000)).as("kept on screen").contains(",2,60,60,1600,1");
    }

    @Test
    void generate_writes_valid_ass_file() throws IOException {
        var words = List.of(
                word("esto", 1_000, 1_400),
                word("es", 1_500, 1_700),
                word("una", 1_800, 2_000),
                word("prueba", 2_100, 2_800)
        );
        Path dest = tempDir.resolve("clip.ass");
        gen.generate(words, 1_000, dest); // clipStartMs = 1000 → times become 0-based

        assertThat(Files.exists(dest)).isTrue();
        String content = Files.readString(dest);
        assertThat(content).contains("[Script Info]");
        assertThat(content).contains("[V4+ Styles]");
        assertThat(content).contains("[Events]");
        assertThat(content).contains("Dialogue:");
        assertThat(content).contains("{\\kf");
    }

    @Test
    void generate_shifts_timestamps_to_clip_relative() throws IOException {
        // Words at 60000-61000 ms absolute; clip starts at 60000 → should appear at 0-1 s
        var words = List.of(
                word("test", 60_000, 61_000)
        );
        Path dest = tempDir.resolve("shifted.ass");
        gen.generate(words, 60_000, dest);

        String content = Files.readString(dest);
        // Time 0:00:00.00 should appear (0 ms offset)
        assertThat(content).contains("0:00:00.00");
    }
}
