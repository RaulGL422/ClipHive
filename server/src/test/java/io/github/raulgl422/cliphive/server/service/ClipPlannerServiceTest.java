package io.github.raulgl422.cliphive.server.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClipPlannerServiceTest {

    private final ClipPlannerService planner =
            new ClipPlannerService(null, null, null, null, null);

    // ── Segment helpers ───────────────────────────────────────────────────────

    private static Map<String, Object> segment(long startMs, long endMs, List<Map<String, Object>> words) {
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("startMs", startMs);
        seg.put("endMs", endMs);
        seg.put("words", words);
        return seg;
    }

    private static Map<String, Object> word(String text, long startMs, long endMs) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("text", text);
        w.put("startMs", startMs);
        w.put("endMs", endMs);
        return w;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void empty_segments_returns_no_ranges() {
        assertThat(planner.buildRanges(List.of())).isEmpty();
    }

    @Test
    void single_short_segment_becomes_one_clip() {
        var words = List.of(
                word("hello", 0, 500),
                word("world", 600, 1200)
        );
        var segments = List.of(segment(0, 1200, words));
        var ranges = planner.buildRanges(segments);

        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0).startMs()).isEqualTo(0);
        assertThat(ranges.get(0).endMs()).isEqualTo(1200);
        assertThat(ranges.get(0).words()).hasSize(2);
    }

    @Test
    void long_content_splits_at_target_duration() {
        // 120 s of continuous speech: should produce at least 2 clips
        List<Map<String, Object>> words = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long start = i * 600L;
            words.add(word("word" + i, start, start + 500));
        }
        var segments = List.of(segment(0, 200 * 600L, words));
        var ranges = planner.buildRanges(segments);

        assertThat(ranges.size()).isGreaterThanOrEqualTo(2);
        // Each clip should respect the target duration (with some tolerance)
        for (var range : ranges) {
            assertThat(range.endMs() - range.startMs())
                    .isLessThanOrEqualTo(ClipPlannerService.TARGET_CLIP_DURATION_MS + 5000);
        }
    }

    @Test
    void pause_is_preferred_cut_point() {
        // Two blocks of 40 s with a 2 s gap — should cut at the gap, not mid-sentence
        List<Map<String, Object>> block1 = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            block1.add(word("a" + i, i * 1000L, i * 1000L + 800));
        }
        List<Map<String, Object>> block2 = new ArrayList<>();
        long block2Start = 42_000; // 2 s gap after block1 ends at 40,000 ms
        for (int i = 0; i < 40; i++) {
            block2.add(word("b" + i, block2Start + i * 1000L, block2Start + i * 1000L + 800));
        }
        var segs = List.of(
                segment(0, 40_000, block1),
                segment(42_000, 42_000 + 39_800, block2)
        );
        var ranges = planner.buildRanges(segs);

        // The cut should occur near the 40 s / 42 s boundary, not in the middle of a block
        assertThat(ranges).hasSizeGreaterThanOrEqualTo(2);
        // First clip ends at or near 40 s
        assertThat(ranges.get(0).endMs()).isBetween(35_000L, 45_000L);
    }

    @Test
    void very_short_tail_is_merged_with_previous_clip() {
        // 61 s of speech + 3 s tail — the 3 s tail should merge into previous clip
        List<Map<String, Object>> mainWords = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            mainWords.add(word("w" + i, i * 1000L, i * 1000L + 900));
        }
        // A 5 s gap then a 3 s tail
        List<Map<String, Object>> tailWords = List.of(
                word("tail1", 66_000, 67_000),
                word("tail2", 67_100, 68_000)
        );
        var segs = List.of(
                segment(0, 61_000, mainWords),
                segment(66_000, 68_000, tailWords)
        );
        var ranges = planner.buildRanges(segs);

        // The tail (2 s) is < MIN_CLIP_DURATION_MS and should be merged
        long lastDuration = ranges.get(ranges.size() - 1).endMs()
                - ranges.get(ranges.size() - 1).startMs();
        // If it was merged, last clip should end at 68 s and be longer than MIN
        assertThat(lastDuration).isGreaterThanOrEqualTo(ClipPlannerService.MIN_CLIP_DURATION_MS);
    }

    @Test
    void ranges_cover_all_words_without_gap() {
        List<Map<String, Object>> words = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            words.add(word("w" + i, i * 800L, i * 800L + 700));
        }
        var segs = List.of(segment(0, 100 * 800L, words));
        var ranges = planner.buildRanges(segs);

        // All ranges together should cover all words
        int totalWords = ranges.stream().mapToInt(r -> r.words().size()).sum();
        assertThat(totalWords).isEqualTo(100);
    }
}
