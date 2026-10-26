package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.model.enums.ScheduledPostOrigin;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.LogEventRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What the cleanup sweep deletes and what it never touches (DESIGN.md §15). */
@SpringBootTest(properties = "cliphive.publishing.scheduler-enabled=false")
@ActiveProfiles("embedded")
class FileCleanupServiceTest {

    @Autowired FileCleanupService cleanup;
    @Autowired VideoJobRepository jobs;
    @Autowired ClipRepository clips;
    @Autowired ScheduledPostRepository posts;
    @Autowired LogEventRepository logRepository;
    @Autowired LogEventService logEvents;
    @Autowired JobFiles files;
    @Autowired JdbcTemplate jdbc;

    private final List<Path> created = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        posts.deleteAll();
        clips.deleteAll();
        jobs.deleteAll();
        for (Path p : created) FileSystemUtils.deleteRecursively(p);
    }

    private VideoJob jobIn(VideoJobState state) throws Exception {
        VideoJob job = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        jdbc.update("UPDATE video_job SET state = ? WHERE id = ?", state.name(), job.getId());
        Path dir = jobDir(job.getId());
        created.add(dir);
        write(dir.resolve("source").resolve("source.mp4"));
        write(dir.resolve("audio").resolve("audio.wav"));
        write(dir.resolve("chunks").resolve("chunk_000.json"));
        return jobs.findById(job.getId()).orElseThrow();
    }

    private Clip clip(VideoJob job, int index, ClipState state) throws Exception {
        Clip clip = new Clip(job, index, List.of());
        clip.setState(state);
        clip = clips.save(clip);
        write(files.clipDir(job.getId(), index).resolve("source.mp4"));
        write(files.renderedClip(job.getId(), index));
        return clip;
    }

    private ScheduledPost post(Clip clip, Platform platform, ScheduledPostState state) {
        ScheduledPost p = new ScheduledPost(clip, platform, Instant.now(), ScheduledPostOrigin.IMMEDIATE);
        p.setState(state);
        return posts.save(p);
    }

    private Path jobDir(UUID id) {
        return files.dataDir().resolve("jobs").resolve(id.toString());
    }

    private static void write(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[4096]);
    }

    @Test
    void a_rendered_job_loses_its_source_but_keeps_the_clips_waiting_to_publish() throws Exception {
        VideoJob job = jobIn(VideoJobState.PUBLISHING);
        Clip published = clip(job, 0, ClipState.PUBLISHED);
        post(published, Platform.YOUTUBE, ScheduledPostState.PUBLISHED);
        post(published, Platform.TIKTOK, ScheduledPostState.CANCELLED);
        Clip scheduled = clip(job, 1, ClipState.SCHEDULED);
        post(scheduled, Platform.YOUTUBE, ScheduledPostState.PUBLISHED);
        post(scheduled, Platform.TIKTOK, ScheduledPostState.SCHEDULED);
        Clip waiting = clip(job, 2, ClipState.RENDERED); // the Claude planner hasn't got to it

        FileCleanupService.Report report = cleanup.sweep();

        Path dir = jobDir(job.getId());
        assertThat(dir.resolve("source")).doesNotExist();
        assertThat(dir.resolve("audio")).doesNotExist();
        assertThat(dir.resolve("chunks")).doesNotExist();
        assertThat(files.clipDir(job.getId(), 0)).as("nothing left to upload").doesNotExist();
        assertThat(files.renderedClip(job.getId(), 1)).as("TikTok still pending").exists();
        assertThat(files.renderedClip(job.getId(), 2)).as("waiting for a plan").exists();
        assertThat(clips.findById(published.getId()).orElseThrow().getState()).isEqualTo(ClipState.CLEANED);
        assertThat(clips.findById(waiting.getId()).orElseThrow().getState()).isEqualTo(ClipState.RENDERED);
        assertThat(report.bytes()).isEqualTo(5 * 4096);
    }

    @Test
    void a_job_still_processing_keeps_its_source() throws Exception {
        VideoJob job = jobIn(VideoJobState.RENDERING);
        clip(job, 0, ClipState.RENDERING);

        cleanup.sweep();

        assertThat(jobDir(job.getId()).resolve("source").resolve("source.mp4")).exists();
        assertThat(files.renderedClip(job.getId(), 0)).exists();
    }

    @Test
    void nothing_is_touched_while_an_upload_is_in_flight() throws Exception {
        VideoJob job = jobIn(VideoJobState.COMPLETED);
        Clip clip = clip(job, 0, ClipState.SCHEDULED);
        post(clip, Platform.INSTAGRAM, ScheduledPostState.PROCESSING); // Instagram reads it from /media

        cleanup.sweep();

        assertThat(files.renderedClip(job.getId(), 0)).exists();
        assertThat(jobDir(job.getId()).resolve("source")).exists();
    }

    @Test
    void a_finished_job_loses_its_whole_folder() throws Exception {
        VideoJob job = jobIn(VideoJobState.COMPLETED);
        Clip clip = clip(job, 0, ClipState.PUBLISHED);
        post(clip, Platform.YOUTUBE, ScheduledPostState.PUBLISHED);

        cleanup.sweep();

        assertThat(jobDir(job.getId())).doesNotExist();
        assertThat(clips.findById(clip.getId()).orElseThrow().getState()).isEqualTo(ClipState.CLEANED);
    }

    @Test
    void a_failed_publication_keeps_its_file_for_n_days_then_gives_up() throws Exception {
        VideoJob job = jobIn(VideoJobState.PUBLISHING);
        Clip fresh = clip(job, 0, ClipState.SCHEDULED);
        post(fresh, Platform.TIKTOK, ScheduledPostState.FAILED);
        Clip stale = clip(job, 1, ClipState.SCHEDULED);
        post(stale, Platform.YOUTUBE, ScheduledPostState.PUBLISHED);
        ScheduledPost old = post(stale, Platform.TIKTOK, ScheduledPostState.FAILED);
        jdbc.update("UPDATE scheduled_post SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(8))), old.getId());

        cleanup.sweep();

        assertThat(files.renderedClip(job.getId(), 0)).as("can still be retried").exists();
        assertThat(files.clipDir(job.getId(), 1)).doesNotExist();
        assertThat(posts.findById(old.getId()).orElseThrow().getState()).isEqualTo(ScheduledPostState.CANCELLED);
    }

    @Test
    void orphan_folders_go_once_they_have_been_quiet_for_n_days() throws Exception {
        Path quiet = jobDir(UUID.randomUUID());
        Path recent = jobDir(UUID.randomUUID());
        created.add(quiet);
        created.add(recent);
        write(quiet.resolve("source").resolve("source.mp4"));
        write(recent.resolve("source").resolve("source.mp4"));
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(10)));
        try (var s = Files.walk(quiet)) {
            for (Path p : s.toList()) Files.setLastModifiedTime(p, old);
        }

        cleanup.sweep();

        assertThat(quiet).doesNotExist();
        assertThat(recent.resolve("source").resolve("source.mp4")).exists();
    }

    @Test
    void old_logs_are_deleted() {
        logEvents.info("limpieza-test", "viejo");
        logEvents.info("limpieza-test", "nuevo");
        jdbc.update("UPDATE log_event SET created_at = ? WHERE component = 'limpieza-test' AND message = 'viejo'",
                Timestamp.from(Instant.now().minus(Duration.ofDays(40))));

        cleanup.sweep();

        assertThat(jdbc.queryForList("SELECT message FROM log_event WHERE component = 'limpieza-test'", String.class))
                .containsExactly("nuevo");
    }

    @Test
    void an_unconfirmed_draft_keeps_its_file_for_n_days() throws Exception {
        VideoJob job = jobIn(VideoJobState.PUBLISHING);
        Clip recent = clip(job, 0, ClipState.PUBLISHED);
        ScheduledPost recentPost = post(recent, Platform.TIKTOK, ScheduledPostState.PUBLISHED);
        Clip old = clip(job, 1, ClipState.PUBLISHED);
        ScheduledPost oldPost = post(old, Platform.TIKTOK, ScheduledPostState.PUBLISHED);
        jdbc.update("UPDATE scheduled_post SET awaiting_confirmation = TRUE, published_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(2))), recentPost.getId());
        jdbc.update("UPDATE scheduled_post SET awaiting_confirmation = TRUE, published_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))), oldPost.getId());

        cleanup.sweep();

        assertThat(files.renderedClip(job.getId(), 0)).as("you may still resend it").exists();
        assertThat(files.renderedClip(job.getId(), 1)).as("past the retention days").doesNotExist();
    }
}
