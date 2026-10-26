package io.github.raulgl422.cliphive.server.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The server's guarantees over Claude's editorial plan (DESIGN.md §10.2). */
class EditorialPlanTest {

    private static final long MIN = 15_000;
    private static final long MAX = 180_000;

    /** {@code n} segments of 10 s each; every third one ends a sentence. */
    private static List<EditorialPlan.Segment> segments(int n) {
        List<EditorialPlan.Segment> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new EditorialPlan.Segment(i, i * 10_000L, (i + 1) * 10_000L, i % 3 == 2 ? "fin." : "sigue"));
        }
        return out;
    }

    private static EditorialPlan.Section section(String title, int[]... clips) {
        return new EditorialPlan.Section(title, List.of(clips));
    }

    private static List<String> ranges(EditorialPlan.Result r) {
        return r.clips().stream().map(c -> c.from() + "-" + c.to() + " " + c.section() + " " + c.part() + "/" + c.parts()).toList();
    }

    @Test
    void a_complete_plan_is_kept_and_parts_are_numbered() {
        var r = EditorialPlan.build(segments(12), null, null, Set.of(), List.of(
                section("Intro del tema", new int[]{0, 3}),
                section("La remontada", new int[]{4, 7}, new int[]{8, 11})), MIN, MAX, true);

        assertThat(ranges(r)).containsExactly("0-3 Intro del tema 1/1", "4-7 La remontada 1/2", "8-11 La remontada 2/2");
        assertThat(r.notes()).isEmpty();
        assertThat(r.proposedCoverage()).isEqualTo(1.0);
    }

    @Test
    void nothing_is_skipped_with_full_coverage() {
        // 16 segments (2:40); Claude only picked 0-3 and 10-13: 4-9 and 14-15 are missing
        var r = EditorialPlan.build(segments(16), null, null, Set.of(), List.of(
                section("A", new int[]{0, 3}), section("B", new int[]{10, 13})), MIN, MAX, true);

        assertThat(ranges(r)).containsExactly("0-9 A 1/1", "10-15 B 1/1");
        assertThat(r.proposedCoverage()).isEqualTo(0.5);
        assertThat(r.notes()).anyMatch(n -> n.contains("Faltaba el tramo 0:40–1:40"));

        var highlights = EditorialPlan.build(segments(16), null, null, Set.of(), List.of(
                section("A", new int[]{0, 3}), section("B", new int[]{10, 13})), MIN, MAX, false);
        assertThat(ranges(highlights)).containsExactly("0-3 A 1/1", "10-13 B 1/1");
    }

    @Test
    void filler_is_cut_out_and_never_covered() {
        // Segment 5 is a sponsor read in the middle of the only clip; 0 is the intro
        var r = EditorialPlan.build(segments(12), 1, null, Set.of(5), List.of(
                section("Todo", new int[]{0, 11})), MIN, MAX, true);

        assertThat(ranges(r)).containsExactly("1-4 Todo 1/2", "6-11 Todo 2/2");
        assertThat(r.notes()).anyMatch(n -> n.contains("relleno"));
    }

    @Test
    void long_clips_are_split_at_sentence_ends_never_cut_dry() {
        // 50 segments = 8:20 in one clip: 3 parts, each ending at a "fin." segment
        var r = EditorialPlan.build(segments(50), null, null, Set.of(), List.of(
                section("Historia", new int[]{0, 49})), MIN, MAX, true);

        assertThat(r.clips()).hasSize(3);
        assertThat(r.clips()).allMatch(c -> c.endMs() - c.startMs() <= MAX);
        assertThat(r.clips().subList(0, 2)).allMatch(c -> c.to() % 3 == 2);
        assertThat(r.clips().getFirst().from()).isZero();
        assertThat(r.clips().getLast().to()).isEqualTo(49);
        for (int i = 1; i < 3; i++) {
            assertThat(r.clips().get(i).from()).as("continuous").isEqualTo(r.clips().get(i - 1).to() + 1);
        }
        assertThat(r.clips()).extracting(EditorialPlan.PlannedClip::parts).containsOnly(3);
    }

    @Test
    void short_clips_join_their_neighbour_and_overlaps_are_resolved() {
        var r = EditorialPlan.build(segments(10), null, null, Set.of(), List.of(
                section("A", new int[]{0, 4}, new int[]{4, 5}, new int[]{6, 9})), MIN, MAX, true);

        // 4-5 overlaps → 5-5 (10 s, too short) → joins 0-4
        assertThat(ranges(r)).containsExactly("0-5 A 1/2", "6-9 A 2/2");
        assertThat(r.notes()).anyMatch(n -> n.contains("solapaban")).anyMatch(n -> n.contains("muy corto"));
    }
}
