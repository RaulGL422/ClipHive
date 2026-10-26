package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.PostStats;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.Transcript;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.Worker;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.model.enums.ScheduledPostOrigin;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.PostStatsRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.repository.WorkerRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Superadmin job actions (DESIGN.md §12.2) against the embedded database. */
@SpringBootTest
@ActiveProfiles("embedded")
class JobControlServiceTest {

    @Autowired JobControlService jobControl;
    @Autowired TaskLifecycleService taskLifecycle;
    @Autowired TaskRepository taskRepository;
    @Autowired ClipRepository clipRepository;
    @Autowired VideoJobRepository jobRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext wac;
    @Autowired ScheduledPostRepository postRepository;
    @Autowired PostStatsRepository postStatsRepository;
    @Autowired TranscriptRepository transcriptRepository;
    @Autowired JobFiles files;

    @AfterEach
    void tearDown() {
        postStatsRepository.deleteAll();
        postRepository.deleteAll();
        transcriptRepository.deleteAll();
        taskRepository.deleteAll();
        clipRepository.deleteAll();
        jobRepository.deleteAll();
        workerRepository.deleteAll();
    }

    /** A job in RENDERING with one clip and its render task, which failed for good. */
    private Task failedRenderTask(VideoJob job) {
        Clip clip = clipRepository.save(new Clip(job, 0, List.of()));
        Task task = Task.forRenderClip(clip, Map.of());
        task.recordFailure(1); // max 1 → FAILED right away
        return taskRepository.save(task);
    }

    private VideoJob jobIn(VideoJobState state) {
        VideoJob job = jobRepository.save(new VideoJob("https://example.com/v", "es", "auto", false));
        jdbc.update("UPDATE video_job SET state = ? WHERE id = ?", state.name(), job.getId());
        return jobRepository.findById(job.getId()).orElseThrow();
    }

    @Test
    void retry_puts_failed_tasks_back_in_the_queue_with_fresh_counters() {
        VideoJob job = jobIn(VideoJobState.RENDERING);
        Task task = failedRenderTask(job);
        int attemptBefore = task.getAttempt();

        int retried = jobControl.retryFailedTasks(job.getId());

        Task after = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(retried).isEqualTo(1);
        assertThat(after.getState()).isEqualTo(TaskState.PENDING);
        assertThat(after.getFailureCount()).isZero();
        assertThat(after.getAttempt()).isGreaterThan(attemptBefore);
    }

    @Test
    void retry_does_nothing_on_a_finished_job() {
        VideoJob job = jobIn(VideoJobState.CANCELLED);
        failedRenderTask(job);

        assertThat(jobControl.retryFailedTasks(job.getId())).isZero();
    }

    @Test
    void cancel_stops_job_tasks_and_clips_and_late_worker_messages_are_ignored() {
        VideoJob job = jobIn(VideoJobState.RENDERING);
        Worker worker = workerRepository.save(new Worker("portatil", "hash-1"));
        Clip clip = clipRepository.save(new Clip(job, 0, List.of()));
        Task running = Task.forRenderClip(clip, Map.of());
        running.assignTo(worker);
        running.setState(TaskState.RUNNING);
        running = taskRepository.save(running);
        int workerAttempt = running.getAttempt();

        assertThat(jobControl.cancel(job.getId())).isTrue();

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getState())
                .isEqualTo(VideoJobState.CANCELLED);
        assertThat(taskRepository.findById(running.getId()).orElseThrow().getState())
                .isEqualTo(TaskState.CANCELLED);
        assertThat(clipRepository.findById(clip.getId()).orElseThrow().getState())
                .isEqualTo(ClipState.CANCELLED);

        // The worker hadn't heard of the cancel yet and reports a failure with
        // its old attempt: it must not put the task back in the queue.
        taskLifecycle.fail(running.getId(), workerAttempt, "interrupted", worker.getId());
        assertThat(taskRepository.findById(running.getId()).orElseThrow().getState())
                .isEqualTo(TaskState.CANCELLED);

        assertThat(jobControl.cancel(job.getId())).as("already finished").isFalse();
    }

    @Test
    void relaunch_creates_a_new_job_with_the_same_link_only_for_finished_jobs() {
        VideoJob running = jobIn(VideoJobState.RENDERING);
        assertThat(jobControl.relaunch(running.getId())).isNull();

        VideoJob failed = jobIn(VideoJobState.FAILED);
        VideoJob fresh = jobControl.relaunch(failed.getId());

        assertThat(fresh).isNotNull();
        assertThat(fresh.getId()).isNotEqualTo(failed.getId());
        assertThat(fresh.getSourceUrl()).isEqualTo(failed.getSourceUrl());
        assertThat(fresh.getLanguage()).isEqualTo(failed.getLanguage());
    }

    @Test
    void job_page_lists_render_tasks_with_worker_name_and_retry_button() throws Exception {
        VideoJob job = jobIn(VideoJobState.RENDERING);
        Worker worker = workerRepository.save(new Worker("portatil", "hash-2"));
        Clip clip = clipRepository.save(new Clip(job, 0, List.of()));
        Task task = Task.forRenderClip(clip, Map.of());
        task.assignTo(worker);
        task.recordFailure(1);
        taskRepository.save(task);

        // Security filters left out: this checks the controller + template only.
        MockMvcBuilders.webAppContextSetup(wac).build()
                .perform(get("/admin/jobs/" + job.getId())
                        // the head fragment reads the CSRF token the security filters would add
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("RENDER_CLIP")))
                .andExpect(content().string(Matchers.containsString("Reintentar tareas fallidas (1)")))
                .andExpect(content().string(Matchers.containsString("Cancelar")));
    }

    @Test
    void delete_removes_the_job_its_rows_and_its_folder() throws Exception {
        VideoJob job = jobIn(VideoJobState.COMPLETED);
        Task task = failedRenderTask(job);
        Clip clip = clipRepository.findByVideoJobIdOrderByClipIndex(job.getId()).getFirst();
        var post = postRepository.save(new ScheduledPost(clip,
                Platform.YOUTUBE, Instant.now(),
                ScheduledPostOrigin.IMMEDIATE));
        jdbc.update("UPDATE scheduled_post SET state = 'PUBLISHED' WHERE id = ?", post.getId());
        postStatsRepository.save(new PostStats(post, Map.of("views", 10L)));
        transcriptRepository.save(new Transcript(job, "es", Map.of("segments", List.of())));
        Path clipFile = files.renderedClip(job.getId(), 0);
        Files.createDirectories(clipFile.getParent());
        Files.write(clipFile, new byte[8]);
        // Another job must be left alone
        VideoJob other = jobIn(VideoJobState.COMPLETED);
        clipRepository.save(new Clip(other, 0, List.of()));

        assertThat(jobControl.delete(job.getId())).isTrue();

        assertThat(jobRepository.findById(job.getId())).isEmpty();
        assertThat(taskRepository.findById(task.getId())).isEmpty();
        assertThat(postRepository.count()).isZero();
        assertThat(postStatsRepository.count()).isZero();
        assertThat(transcriptRepository.findByVideoJobId(job.getId())).isEmpty();
        assertThat(files.dataDir().resolve("jobs").resolve(job.getId().toString())).doesNotExist();
        assertThat(clipRepository.findByVideoJobIdOrderByClipIndex(other.getId())).hasSize(1);
        assertThat(jobControl.delete(job.getId())).as("already gone").isFalse();
    }

    @Test
    void a_running_job_is_cancelled_and_deleted_but_not_while_uploading() {
        VideoJob running = jobIn(VideoJobState.RENDERING);
        failedRenderTask(running);
        assertThat(jobControl.delete(running.getId())).isTrue();
        assertThat(jobRepository.findById(running.getId())).isEmpty();

        VideoJob uploading = jobIn(VideoJobState.PUBLISHING);
        Clip clip = clipRepository.save(new Clip(uploading, 0, List.of()));
        var post = postRepository.save(new ScheduledPost(clip,
                Platform.YOUTUBE, Instant.now(),
                ScheduledPostOrigin.IMMEDIATE));
        jdbc.update("UPDATE scheduled_post SET state = 'UPLOADING' WHERE id = ?", post.getId());

        assertThatThrownBy(() -> jobControl.delete(uploading.getId()))
                .isInstanceOf(JobControlService.DeleteRefusedException.class)
                .hasMessageContaining("subiéndose");
        assertThat(jobRepository.findById(uploading.getId())).isPresent();
    }
}
