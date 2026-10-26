package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.Transcript;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.ClipMode;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Manual clip mode (DESIGN.md §4.1) on the embedded DB. */
@SpringBootTest
@ActiveProfiles("embedded")
class ManualSelectionTest {

    @Autowired ManualSelectionService selection;
    @Autowired RenderSetupService renderSetup;
    @Autowired VideoJobRepository jobs;
    @Autowired ClipRepository clips;
    @Autowired TaskRepository tasks;
    @Autowired TranscriptRepository transcripts;
    @Autowired ContentAnalysisRepository analyses;
    @Autowired JobFiles files;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext wac;

    private VideoJob job;

    @AfterEach
    void tearDown() throws Exception {
        tasks.deleteAll();
        clips.deleteAll();
        transcripts.deleteAll();
        analyses.deleteAll();
        jobs.deleteAll();
        if (job != null) FileSystemUtils.deleteRecursively(files.dataDir().resolve("jobs").resolve(job.getId().toString()));
    }

    private VideoJob manualJob(VideoJobState state) {
        VideoJob j = new VideoJob("https://example.com/v", "es", "auto", false);
        j.setClipMode(ClipMode.MANUAL);
        j = jobs.save(j);
        jdbc.update("UPDATE video_job SET state = ?, duration_seconds = 600 WHERE id = ?", state.name(), j.getId());
        return jobs.findById(j.getId()).orElseThrow();
    }

    private List<Map<String, Object>> ranges() {
        return jobs.findById(job.getId()).orElseThrow().getManualRangesJson();
    }

    private VideoJobState state() {
        return jobs.findById(job.getId()).orElseThrow().getState();
    }

    @Test
    void ranges_are_validated_and_kept_sorted() {
        job = manualJob(VideoJobState.TRANSCRIBING);

        selection.addRange(job.getId(), 120_000, 150_000);
        selection.addRange(job.getId(), 10_000, 40_000);
        assertThat(ranges()).extracting(r -> ((Number) r.get("startMs")).longValue()).containsExactly(10_000L, 120_000L);

        assertThatThrownBy(() -> selection.addRange(job.getId(), 0, 5_000))
                .hasMessageContaining("mínimo");
        assertThatThrownBy(() -> selection.addRange(job.getId(), 0, 400_000))
                .hasMessageContaining("máximo");
        assertThatThrownBy(() -> selection.addRange(job.getId(), 30_000, 60_000))
                .hasMessageContaining("solapa");
        assertThatThrownBy(() -> selection.addRange(job.getId(), 590_000, 620_000))
                .hasMessageContaining("final del vídeo");

        selection.removeRange(job.getId(), 0);
        assertThat(ranges()).hasSize(1);
    }

    @Test
    void confirming_locks_the_ranges() {
        job = manualJob(VideoJobState.TRANSCRIBING);
        assertThatThrownBy(() -> selection.confirm(job.getId())).hasMessageContaining("al menos un clip");

        selection.addRange(job.getId(), 10_000, 40_000);
        selection.confirm(job.getId());

        assertThat(jobs.findById(job.getId()).orElseThrow().isSelectionConfirmed()).isTrue();
        assertThat(state()).as("transcription still running: state untouched").isEqualTo(VideoJobState.TRANSCRIBING);
        assertThatThrownBy(() -> selection.addRange(job.getId(), 100_000, 130_000))
                .hasMessageContaining("ya no se pueden cambiar");
    }

    @Test
    void ranges_snap_to_word_edges() {
        Map<String, Object> stitched = Map.of("segments", List.of(Map.of("words", List.of(
                Map.of("text", "hola", "startMs", 9_800, "endMs", 10_300),
                Map.of("text", "adiós", "startMs", 39_700, "endMs", 40_400)))));

        List<Map<String, Object>> snapped = ManualSelectionService.snapToWords(
                List.of(Map.of("startMs", 10_000L, "endMs", 40_000L)), stitched);

        assertThat(snapped.getFirst()).containsEntry("startMs", 9_800L).containsEntry("endMs", 40_400L);
    }

    @Test
    void job_waits_for_the_selection_then_plans_the_chosen_clips_without_claude() throws Exception {
        job = manualJob(VideoJobState.ANALYZING);
        transcripts.save(new Transcript(job, "es", Map.of("language", "es", "segments", List.of(Map.of(
                "text", "hola adiós",
                "words", List.of(
                        Map.of("text", "hola", "startMs", 9_800, "endMs", 10_300),
                        Map.of("text", "adiós", "startMs", 39_700, "endMs", 40_400)))))));

        // Transcription done, nothing confirmed yet → waits
        renderSetup.setup(job.getId());
        assertThat(state()).isEqualTo(VideoJobState.AWAITING_SELECTION);

        selection.addRange(job.getId(), 10_000, 40_000);
        selection.confirm(job.getId());
        assertThat(state()).isEqualTo(VideoJobState.ANALYZING);

        // The render trigger (every 5 s) picks it up: one clip from the chosen range,
        // snapped to the words, and no Claude content analysis.
        long deadline = System.currentTimeMillis() + 15_000;
        while (clips.findByVideoJobIdOrderByClipIndex(job.getId()).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        List<Clip> planned = clips.findByVideoJobIdOrderByClipIndex(job.getId());
        assertThat(planned).hasSize(1);
        assertThat(planned.getFirst().getKeptRangesJson().getFirst())
                .containsEntry("startMs", 9_800).containsEntry("endMs", 40_400);
        assertThat(analyses.findByVideoJobId(job.getId())).isEmpty();
    }

    @Test
    void job_page_shows_the_player_and_selection_form() throws Exception {
        job = manualJob(VideoJobState.TRANSCRIBING);
        Path source = files.dataDir().resolve("jobs").resolve(job.getId().toString()).resolve("source").resolve("source.mp4");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[2048]);

        var mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/admin/jobs/" + job.getId())
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("src-player")))
                .andExpect(content().string(containsString("Marcar inicio aquí")));

        mvc.perform(get("/admin/jobs/" + job.getId() + "/source").header("Range", "bytes=0-9"))
                .andExpect(status().isPartialContent());
    }
}
