package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.llm.LlmClient;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.model.enums.ScheduledPostOrigin;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.PublicationPlanRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.SettingRepository;
import io.github.raulgl422.cliphive.server.repository.SocialAccountRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.security.EncryptionService;
import io.github.raulgl422.cliphive.server.service.SettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Claude publication planner end to end on the embedded DB (DESIGN.md
 * §10.4), with Claude mocked and "now" pinned to a morning so the day's
 * window is predictable. YouTube and TikTok are connected.
 */
@SpringBootTest(properties = {"cliphive.planner.scheduler-enabled=false", "cliphive.publishing.scheduler-enabled=false"})
@ActiveProfiles("embedded")
class PublicationPlannerTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @MockitoBean LlmClient llm;

    @Autowired PublicationPlanner planner;
    @Autowired SettingService settings;
    @Autowired SettingRepository settingRepository;
    @Autowired ScheduledPostRepository posts;
    @Autowired PublicationPlanRepository plans;
    @Autowired ClipRepository clips;
    @Autowired VideoJobRepository jobs;
    @Autowired SocialAccountRepository accounts;
    @Autowired EncryptionService encryption;
    @Autowired JdbcTemplate jdbc;

    private VideoJob job;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        reset(llm);
        settings.set(SettingService.PUBLISH_MODE, "claude");
        accounts.save(new SocialAccount(Platform.YOUTUBE, "yt", encryption.encrypt("tok")));
        accounts.save(new SocialAccount(Platform.TIKTOK, "tt", encryption.encrypt("tok")));
        today = LocalDate.now(MADRID);
        pinClock("08:00");
    }

    @AfterEach
    void tearDown() {
        planner.setClock(Clock.system(MADRID));
        posts.deleteAll();
        plans.deleteAll();
        clips.deleteAll();
        jobs.deleteAll();
        accounts.deleteAll();
        settingRepository.deleteAll();
    }

    private void pinClock(String hhmm) {
        ZonedDateTime at = today.atTime(LocalTime.parse(hhmm)).atZone(MADRID);
        planner.setClock(Clock.fixed(at.toInstant(), MADRID));
    }

    /** A RENDERED job with {@code n} rendered clips. */
    private VideoJob renderedJob(int n) {
        VideoJob j = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        jdbc.update("UPDATE video_job SET state = 'RENDERED', title = 'Mi vídeo' WHERE id = ?", j.getId());
        for (int i = 0; i < n; i++) {
            Clip c = clips.save(new Clip(j, i, List.of()));
            jdbc.update("UPDATE clip SET state = 'RENDERED', title = ? WHERE id = ?", "Clip " + (i + 1), c.getId());
        }
        return jobs.findById(j.getId()).orElseThrow();
    }

    private List<ScheduledPost> planned() {
        return posts.findAll().stream().sorted(Comparator.comparing(ScheduledPost::getScheduledAt)).toList();
    }

    private String madrid(ScheduledPost p) {
        return p.getPlatform() + "=" + p.getScheduledAt().atZone(MADRID).toLocalTime();
    }

    @Test
    void claudes_plan_is_validated_and_scheduled() throws Exception {
        job = renderedJob(3);
        when(llm.call(anyString(), anyString())).thenReturn("""
                Aquí tienes el plan:
                {"publicaciones": [{"clip": "c1", "hora": "13:00"}, {"clip": "c2", "hora": "13:30"}],
                 "explicacion": "Dos clips al mediodía."}""");

        assertThat(planner.plan("prueba")).isEqualTo(4);

        verify(llm).call(anyString(), contains("\"hora_minima\":\"08:10\""));
        assertThat(planned()).extracting(this::madrid)
                .containsExactlyInAnyOrder("YOUTUBE=13:00", "TIKTOK=13:00", "YOUTUBE=14:30", "TIKTOK=14:30"); // 13:30 → gap
        assertThat(planned()).allMatch(p -> p.getOrigin() == ScheduledPostOrigin.CLAUDE_PLAN);
        List<Clip> jobClips = clips.findByVideoJobIdOrderByClipIndex(job.getId());
        assertThat(jobClips).extracting(Clip::getState)
                .containsExactly(ClipState.SCHEDULED, ClipState.SCHEDULED, ClipState.RENDERED);
        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(VideoJobState.SCHEDULED);

        PublicationPlanner.PlanView view = planner.today().orElseThrow();
        assertThat(view.source()).isEqualTo("Claude");
        assertThat(view.explanation()).contains("Dos clips");
        assertThat(view.scheduled()).isEqualTo(4);
        assertThat(view.adjustments()).anyMatch(a -> a.contains("13:30"));
    }

    @Test
    void if_claude_fails_the_default_slots_keep_publishing_going() throws Exception {
        job = renderedJob(3);
        when(llm.call(anyString(), anyString())).thenThrow(new IOException("claude -p timed out"));

        planner.plan("prueba");

        assertThat(planned()).extracting(this::madrid)
                .containsExactlyInAnyOrder("YOUTUBE=13:00", "TIKTOK=13:00", "YOUTUBE=20:00", "TIKTOK=20:00");
        assertThat(planner.today().orElseThrow().source()).isEqualTo("Franjas por defecto");
    }

    @Test
    void a_replan_only_adds_and_never_moves_what_is_already_scheduled() throws Exception {
        job = renderedJob(2);
        when(llm.call(anyString(), anyString())).thenReturn("""
                {"publicaciones": [{"clip": "c1", "plataforma": "youtube", "hora": "12:00"},
                                   {"clip": "c1", "plataforma": "tiktok", "hora": "12:00"},
                                   {"clip": "c2", "plataforma": "youtube", "hora": "18:00"},
                                   {"clip": "c2", "plataforma": "tiktok", "hora": "18:00"}]}""");
        planner.plan("prueba");
        // The superadmin moves clip 1's YouTube publication by hand
        ScheduledPost edited = planned().stream().filter(p -> p.getPlatform() == Platform.YOUTUBE).findFirst().orElseThrow();
        edited.reschedule(today.atTime(11, 0).atZone(MADRID).toInstant());
        posts.save(edited);

        // A new clip arrives; the re-plan places it and leaves the rest alone — it used to
        // re-place untouched publications, which kept pushing them later (DESIGN.md §10.4)
        Clip third = clips.save(new Clip(job, 2, List.of()));
        jdbc.update("UPDATE clip SET state = 'RENDERED' WHERE id = ?", third.getId());
        when(llm.call(anyString(), anyString())).thenReturn("""
                {"publicaciones": [{"clip": "c1", "plataforma": "youtube", "hora": "21:00"},
                                   {"clip": "c1", "plataforma": "tiktok", "hora": "21:00"}]}""");
        pinClock("10:00");
        planner.plan("replanificación manual");

        assertThat(planned()).extracting(this::madrid)
                .containsExactlyInAnyOrder("YOUTUBE=11:00", "TIKTOK=12:00", "YOUTUBE=18:00", "TIKTOK=18:00",
                        "YOUTUBE=21:00", "TIKTOK=21:00");
        assertThat(clips.findByVideoJobIdOrderByClipIndex(job.getId())).extracting(Clip::getState)
                .containsOnly(ClipState.SCHEDULED);
        assertThat(plans.findAll()).as("one plan per day, overwritten").hasSize(1);
    }

    @Test
    void the_check_plans_after_7_and_replans_when_3_new_clips_arrive() throws Exception {
        job = renderedJob(1);
        when(llm.call(anyString(), anyString())).thenReturn("{\"publicaciones\": []}");

        pinClock("06:30");
        planner.check();
        verify(llm, never()).call(anyString(), anyString());

        pinClock("08:00");
        planner.check();
        planner.check(); // already planned, nothing new
        verify(llm).call(anyString(), anyString());

        renderedJob(3); // three new ready clips
        planner.check();
        verify(llm, times(2)).call(anyString(), anyString());
        assertThat(planner.today().orElseThrow().trigger()).isEqualTo("clips nuevos en cola");
    }

    @Test
    void a_clip_saved_again_is_not_new_but_a_job_that_finishes_rendering_is() throws Exception {
        job = renderedJob(3);
        when(llm.call(anyString(), anyString())).thenReturn("{\"publicaciones\": []}");
        planner.check();
        verify(llm).call(anyString(), anyString());

        // The three unplanned clips are saved again (e.g. their texts regenerated): not new
        jdbc.update("UPDATE clip SET updated_at = now() + interval '1 minute' WHERE video_job_id = ?", job.getId());
        planner.check();
        verify(llm).call(anyString(), anyString());

        // A job whose clips rendered long ago reaches RENDERED after the plan: new
        VideoJob late = renderedJob(3);
        jdbc.update("UPDATE clip SET updated_at = now() - interval '2 hours' WHERE video_job_id = ?", late.getId());
        planner.check();
        verify(llm, times(2)).call(anyString(), anyString());
    }

    @Test
    void a_long_queue_raises_the_pace_and_tiktok_drafts_no_longer_cap_the_plan() throws Exception {
        // 20 clips at 3 a day is more than the 5 days that trigger the accelerated pace (max 6)
        job = renderedJob(20);
        StringBuilder out = new StringBuilder("{\"publicaciones\": [");
        String[] hours = {"09:00", "10:30", "12:00", "13:30", "15:00", "16:30", "18:00", "19:30"};
        for (int i = 0; i < hours.length; i++) {
            if (i > 0) out.append(',');
            out.append("{\"clip\": \"c").append(i + 1).append("\", \"plataforma\": \"youtube\", \"hora\": \"")
                    .append(hours[i]).append("\"}, {\"clip\": \"c").append(i + 1)
                    .append("\", \"plataforma\": \"tiktok\", \"hora\": \"").append(hours[i]).append("\"}");
        }
        when(llm.call(anyString(), anyString())).thenReturn(out.append("]}").toString());

        planner.plan("prueba");

        ArgumentCaptor<String> stdin = ArgumentCaptor.forClass(String.class);
        verify(llm).call(anyString(), stdin.capture());
        assertThat(stdin.getValue()).contains("\"modo_acelerado\":true").contains("\"clips_maximos_hoy\":6")
                .contains("\"plataforma\":\"tiktok\",\"tope_restante_hoy\":10")
                .contains("\"calendario\"").contains("\"publico\"");
        // Claude asked for 8 clips: the server keeps today's limit, the first 6 of the video
        assertThat(planned()).hasSize(12);
        assertThat(planned().stream().map(p -> p.getClip().getId()).distinct().count()).isEqualTo(6);
    }

    @Test
    void nothing_runs_in_immediate_mode() throws Exception {
        settings.set(SettingService.PUBLISH_MODE, "immediate");
        job = renderedJob(1);
        planner.check();
        verify(llm, never()).call(anyString(), anyString());
        assertThat(plans.count()).isZero();
    }

    @Test
    void a_platform_that_falls_behind_holds_the_others_back() throws Exception {
        job = renderedJob(5);
        // Two TikTok publications from yesterday still stuck (one postponed by the drafts cap, one failed)
        Clip a = clips.save(new Clip(job, 10, List.of()));
        Clip b = clips.save(new Clip(job, 11, List.of()));
        ScheduledPost waiting = new ScheduledPost(a, Platform.TIKTOK, Instant.now(), ScheduledPostOrigin.CLAUDE_PLAN);
        waiting.postpone("TikTok: hay demasiados borradores", Instant.now().plusSeconds(3600));
        posts.save(waiting);
        ScheduledPost failed = new ScheduledPost(b, Platform.TIKTOK, Instant.now(), ScheduledPostOrigin.CLAUDE_PLAN);
        failed.fail("error");
        posts.save(failed);
        jdbc.update("UPDATE scheduled_post SET scheduled_at = ? WHERE id = ?", Timestamp.from(
                today.minusDays(1).atTime(23, 0).atZone(MADRID).toInstant()), waiting.getId());
        when(llm.call(anyString(), anyString())).thenReturn("""
                {"publicaciones": [{"clip": "c1", "hora": "12:00"}, {"clip": "c2", "hora": "16:00"},
                                   {"clip": "c3", "hora": "20:00"}]}""");

        planner.plan("prueba");

        // Normal pace 3, TikTok 2 behind YouTube: only 1 clip today, on both platforms
        ArgumentCaptor<String> stdin = ArgumentCaptor.forClass(String.class);
        verify(llm).call(anyString(), stdin.capture());
        assertThat(stdin.getValue()).contains("\"clips_menos_hoy_por_retraso\":2");
        assertThat(planned().stream().filter(p -> !p.getClip().getId().equals(a.getId())
                        && !p.getClip().getId().equals(b.getId())))
                .extracting(this::madrid).containsExactlyInAnyOrder("YOUTUBE=12:00", "TIKTOK=12:00");
        assertThat(planner.today().orElseThrow().adjustments()).anyMatch(x -> x.contains("Plataformas a la par"));
    }

    @Test
    void the_video_that_started_first_is_finished_before_any_other() throws Exception {
        // Video B was sent first, but A started publishing first (yesterday at 12:00, B at 20:00):
        // both are in progress, and A's clips go before B's whatever Claude picks
        VideoJob b = renderedJob(3);
        VideoJob a = renderedJob(3);
        job = a;
        publishedYesterday(a, 0, "12:00");
        publishedYesterday(b, 0, "20:00");
        // The queue Claude gets: A1, A2, B1, B2 → c1..c4; Claude goes for B's clips
        when(llm.call(anyString(), anyString())).thenReturn("""
                {"publicaciones": [{"clip": "c3", "hora": "13:00"}, {"clip": "c4", "hora": "19:00"}]}""");

        planner.plan("prueba");

        ArgumentCaptor<String> stdin = ArgumentCaptor.forClass(String.class);
        verify(llm).call(anyString(), stdin.capture());
        assertThat(stdin.getValue()).contains("\"clips_publicados_o_programados\":1");
        List<UUID> scheduled = posts.findAll().stream()
                .filter(p -> p.getState() == ScheduledPostState.SCHEDULED)
                .map(p -> p.getClip().getId()).distinct().toList();
        List<Clip> aClips = clips.findByVideoJobIdOrderByClipIndex(a.getId());
        assertThat(scheduled).containsExactlyInAnyOrder(aClips.get(1).getId(), aClips.get(2).getId());
        assertThat(planner.today().orElseThrow().adjustments()).anyMatch(x -> x.contains("Orden de la cola"));
    }

    /** Clip {@code index} of {@code j} went out on both platforms yesterday at {@code hhmm}. */
    private void publishedYesterday(VideoJob j, int index, String hhmm) {
        Clip c = clips.findByVideoJobIdOrderByClipIndex(j.getId()).get(index);
        Timestamp at = Timestamp.from(
                today.minusDays(1).atTime(LocalTime.parse(hhmm)).atZone(MADRID).toInstant());
        for (Platform platform : List.of(Platform.YOUTUBE, Platform.TIKTOK)) {
            ScheduledPost p = posts.save(new ScheduledPost(c, platform, at.toInstant(), ScheduledPostOrigin.CLAUDE_PLAN));
            jdbc.update("UPDATE scheduled_post SET state = 'PUBLISHED', published_at = ?, scheduled_at = ? WHERE id = ?",
                    at, at, p.getId());
        }
        jdbc.update("UPDATE clip SET state = 'PUBLISHED' WHERE id = ?", c.getId());
    }
}
