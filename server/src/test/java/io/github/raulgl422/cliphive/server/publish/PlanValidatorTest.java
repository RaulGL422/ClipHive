package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.raulgl422.cliphive.server.model.enums.Platform.INSTAGRAM;
import static io.github.raulgl422.cliphive.server.model.enums.Platform.TIKTOK;
import static io.github.raulgl422.cliphive.server.model.enums.Platform.YOUTUBE;
import static org.assertj.core.api.Assertions.assertThat;

/** The server's guarantees over Claude's plan (DESIGN.md §10.4). */
class PlanValidatorTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final UUID JOB_A = UUID.randomUUID();
    private static final UUID JOB_B = UUID.randomUUID();

    /** Queue: video A clips 0–2 (c1–c3), video B clip 0 (c4). */
    private final List<PlanValidator.Candidate> queue = List.of(
            candidate("c1", JOB_A, 0), candidate("c2", JOB_A, 1), candidate("c3", JOB_A, 2), candidate("c4", JOB_B, 0));

    private static PlanValidator.Candidate candidate(String key, UUID job, int index) {
        return new PlanValidator.Candidate(key, UUID.nameUUIDFromBytes(key.getBytes()), job, index, "Clip " + key);
    }

    private static Instant at(String hhmm) {
        return DAY.atTime(LocalTime.parse(hhmm)).atZone(MADRID).toInstant();
    }

    private static PlanValidator.Proposal p(String clip, Platform platform, String hhmm) {
        return new PlanValidator.Proposal(clip, platform, LocalTime.parse(hhmm));
    }

    private PlanValidator.Context ctx(List<Platform> platforms, int cap, Map<Platform, List<Instant>> occupied) {
        Map<Platform, Integer> remaining = new EnumMap<>(Platform.class);
        platforms.forEach(pl -> remaining.put(pl, cap));
        return new PlanValidator.Context(platforms, remaining, occupied, Duration.ofMinutes(90),
                at("09:00"), at("23:30"), DAY, MADRID);
    }

    /** "clip@platform=HH:mm" for readable assertions. */
    private List<String> plan(PlanValidator.Result r) {
        List<String> out = new ArrayList<>();
        for (PlanValidator.Assignment a : r.assignments()) {
            String key = queue.stream().filter(c -> c.clipId().equals(a.clipId())).findFirst().orElseThrow().key();
            out.add(key + "@" + a.platform() + "=" + a.at().atZone(MADRID).toLocalTime());
        }
        return out;
    }

    @Test
    void a_valid_plan_is_kept_as_is() {
        var r = PlanValidator.validate(queue, List.of(
                new PlanValidator.Proposal("c1", LocalTime.of(13, 0)), new PlanValidator.Proposal("c2", LocalTime.of(20, 0))),
                ctx(List.of(YOUTUBE, TIKTOK), 5, Map.of()));

        assertThat(plan(r)).containsExactly("c1@YOUTUBE=13:00", "c1@TIKTOK=13:00", "c2@YOUTUBE=20:00", "c2@TIKTOK=20:00");
        assertThat(r.adjustments()).isEmpty();
    }

    @Test
    void a_clip_goes_out_at_the_same_time_everywhere() {
        // Different times per platform: the earliest one, on all of them
        var unified = PlanValidator.validate(queue, List.of(p("c1", YOUTUBE, "13:00"), p("c1", TIKTOK, "19:00")),
                ctx(List.of(YOUTUBE, TIKTOK), 5, Map.of()));
        assertThat(plan(unified)).containsExactly("c1@YOUTUBE=13:00", "c1@TIKTOK=13:00");
        assertThat(unified.adjustments()).anyMatch(a -> a.contains("sale a las 13:00 en todas"));

        // Something already near 13:30 on TikTok only: the clip moves on both, not just there
        var gap = PlanValidator.validate(queue, List.of(new PlanValidator.Proposal("c1", LocalTime.of(13, 30))),
                ctx(List.of(YOUTUBE, TIKTOK), 5, Map.of(TIKTOK, List.of(at("13:00")))));
        assertThat(plan(gap)).containsExactly("c1@YOUTUBE=14:30", "c1@TIKTOK=14:30");
    }

    @Test
    void past_times_and_the_minimum_gap_are_fixed() {
        var occupied = Map.of(YOUTUBE, List.of(at("12:30")));
        var r = PlanValidator.validate(queue, List.of(
                p("c1", YOUTUBE, "08:00"),    // before the window → 09:00
                p("c2", YOUTUBE, "13:00")),   // 30 min after 12:30 → 14:00
                ctx(List.of(YOUTUBE), 5, occupied));

        assertThat(plan(r)).containsExactly("c1@YOUTUBE=09:00", "c2@YOUTUBE=14:00");
        assertThat(r.adjustments()).hasSize(2).allMatch(a -> a.contains("pasa de"));
    }

    @Test
    void the_daily_cap_and_the_end_of_the_day_leave_clips_for_tomorrow() {
        var capped = PlanValidator.validate(queue, List.of(
                p("c1", YOUTUBE, "10:00"), p("c2", YOUTUBE, "12:00"), p("c4", YOUTUBE, "14:00")),
                ctx(List.of(YOUTUBE), 2, Map.of()));
        assertThat(plan(capped)).containsExactly("c1@YOUTUBE=10:00", "c2@YOUTUBE=12:00");
        assertThat(capped.adjustments()).anyMatch(a -> a.contains("tope diario"));

        var late = PlanValidator.validate(queue, List.of(p("c1", YOUTUBE, "23:00"), p("c4", YOUTUBE, "23:15")),
                ctx(List.of(YOUTUBE), 5, Map.of()));
        assertThat(plan(late)).containsExactly("c1@YOUTUBE=23:00");
        assertThat(late.adjustments()).anyMatch(a -> a.contains("no cabe hoy dentro del horario"));
    }

    @Test
    void a_chosen_clip_goes_to_every_platform_or_waits() {
        // c1 has a time only for YouTube: it's the time on TikTok too
        var filled = PlanValidator.validate(queue, List.of(p("c1", YOUTUBE, "13:00")),
                ctx(List.of(YOUTUBE, TIKTOK), 5, Map.of()));
        assertThat(plan(filled)).containsExactly("c1@YOUTUBE=13:00", "c1@TIKTOK=13:00");

        // Instagram is full today: c1 can't go everywhere, so it isn't published anywhere
        var full = PlanValidator.validate(queue, List.of(p("c1", YOUTUBE, "13:00"), p("c1", INSTAGRAM, "13:00")),
                new PlanValidator.Context(List.of(YOUTUBE, INSTAGRAM), Map.of(YOUTUBE, 5, INSTAGRAM, 0), Map.of(),
                        Duration.ofMinutes(90), at("09:00"), at("23:30"), DAY, MADRID));
        assertThat(full.assignments()).isEmpty();
        assertThat(full.adjustments()).anyMatch(a -> a.contains("Instagram: tope diario alcanzado"));
    }

    @Test
    void clips_of_a_video_go_out_in_order() {
        // Claude picks c2 and c3 (not c1): the video's first two clips go, keeping the times
        var r = PlanValidator.validate(queue, List.of(p("c3", YOUTUBE, "12:00"), p("c2", YOUTUBE, "18:00")),
                ctx(List.of(YOUTUBE), 5, Map.of()));
        assertThat(plan(r)).containsExactly("c1@YOUTUBE=12:00", "c2@YOUTUBE=18:00");
        assertThat(r.adjustments()).anyMatch(a -> a.contains("Orden de la cola"));
    }

    @Test
    void unknown_clips_and_unconnected_platforms_are_ignored() {
        var r = PlanValidator.validate(queue, List.of(p("c99", YOUTUBE, "12:00"), p("c1", INSTAGRAM, "12:00"),
                p("c1", YOUTUBE, "12:00")), ctx(List.of(YOUTUBE), 5, Map.of()));
        assertThat(plan(r)).containsExactly("c1@YOUTUBE=12:00");
        assertThat(r.adjustments()).anyMatch(a -> a.contains("c99")).anyMatch(a -> a.contains("Instagram no está conectado"));
    }

    @Test
    void fallback_uses_the_default_slots_with_the_oldest_clips() {
        List<PlanValidator.Proposal> fallback = PlanValidator.fallback(queue, List.of(YOUTUBE, TIKTOK),
                List.of(LocalTime.of(20, 0), LocalTime.of(13, 0)));
        var r = PlanValidator.validate(queue, fallback, ctx(List.of(YOUTUBE, TIKTOK), 5, Map.of()));
        assertThat(plan(r)).containsExactly("c1@YOUTUBE=13:00", "c1@TIKTOK=13:00", "c2@YOUTUBE=20:00", "c2@TIKTOK=20:00");
    }

    @Test
    void when_only_one_part_fits_it_is_the_first_one() {
        // TikTok has room for one more today and Claude gave part 2 the earlier TikTok time:
        // part 1 takes it, part 2 waits for tomorrow everywhere (never part 2 without part 1)
        var r = PlanValidator.validate(queue, List.of(
                p("c1", YOUTUBE, "13:00"), p("c1", TIKTOK, "21:00"),
                p("c2", YOUTUBE, "15:00"), p("c2", TIKTOK, "15:00")),
                new PlanValidator.Context(List.of(YOUTUBE, TIKTOK), Map.of(YOUTUBE, 5, TIKTOK, 1), Map.of(),
                        Duration.ofMinutes(90), at("09:00"), at("23:30"), DAY, MADRID));

        assertThat(plan(r)).containsExactly("c1@YOUTUBE=13:00", "c1@TIKTOK=13:00");
        assertThat(r.adjustments()).anyMatch(a -> a.contains("TikTok: tope diario alcanzado"));
    }

    @Test
    void picking_another_video_still_publishes_the_next_clips_of_the_queue() {
        // Claude puts B's clip between A's: the times stay, but the queue's order decides which
        // clips go — A isn't finished, so B waits
        var r = PlanValidator.validate(queue, List.of(
                p("c1", YOUTUBE, "13:00"), p("c4", YOUTUBE, "15:00"), p("c2", YOUTUBE, "17:00")),
                ctx(List.of(YOUTUBE), 5, Map.of()));

        assertThat(plan(r)).containsExactly("c1@YOUTUBE=13:00", "c2@YOUTUBE=15:00", "c3@YOUTUBE=17:00");
        assertThat(r.adjustments()).anyMatch(a -> a.contains("Orden de la cola"));
    }

    @Test
    void todays_clip_limit_drops_the_last_ones() {
        var r = PlanValidator.validate(queue, List.of(
                p("c1", YOUTUBE, "10:00"), p("c2", YOUTUBE, "12:00"), p("c3", YOUTUBE, "14:00"), p("c4", YOUTUBE, "16:00")),
                new PlanValidator.Context(List.of(YOUTUBE), Map.of(YOUTUBE, 10), Map.of(), Duration.ofMinutes(90),
                        at("09:00"), at("23:30"), DAY, MADRID, 2));

        assertThat(plan(r)).containsExactly("c1@YOUTUBE=10:00", "c2@YOUTUBE=12:00");
        assertThat(r.adjustments()).anyMatch(a -> a.contains("Límite de hoy"));
    }
}
