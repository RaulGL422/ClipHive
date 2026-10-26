package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.Worker;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.repository.WorkerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RENDERING → RENDERED (DESIGN.md §5.1) and watching/downloading clips, on the embedded DB. */
@SpringBootTest
@ActiveProfiles("embedded")
class RenderCompletionTest {

    @Autowired TaskLifecycleService lifecycle;
    @Autowired JobControlService jobControl;
    @Autowired TaskRepository taskRepository;
    @Autowired ClipRepository clipRepository;
    @Autowired VideoJobRepository jobRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired JobFiles files;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext wac;

    private VideoJob job;

    @AfterEach
    void tearDown() throws Exception {
        taskRepository.deleteAll();
        clipRepository.deleteAll();
        jobRepository.deleteAll();
        workerRepository.deleteAll();
        if (job != null) {
            FileSystemUtils.deleteRecursively(files.dataDir().resolve("jobs").resolve(job.getId().toString()));
        }
    }

    private VideoJob renderingJob() {
        VideoJob j = jobRepository.save(new VideoJob("https://example.com/v", "es", "auto", false));
        jdbc.update("UPDATE video_job SET state = 'RENDERING' WHERE id = ?", j.getId());
        return jobRepository.findById(j.getId()).orElseThrow();
    }

    private Task runningRender(Clip clip, Worker worker) {
        Task t = Task.forRenderClip(clip, Map.of());
        t.assignTo(worker);
        Task saved = taskRepository.save(t);
        lifecycle.accept(saved.getId(), saved.getAttempt(), worker.getId());
        return taskRepository.findById(saved.getId()).orElseThrow();
    }

    private VideoJobState jobState() {
        return jobRepository.findById(job.getId()).orElseThrow().getState();
    }

    private ClipState clipState(Clip clip) {
        return clipRepository.findById(clip.getId()).orElseThrow().getState();
    }

    @Test
    void job_becomes_rendered_once_every_clip_is_rendered_or_failed_and_retry_reopens_it() {
        job = renderingJob();
        Worker worker = workerRepository.save(new Worker("portatil", "h-render"));
        Clip ok = clipRepository.save(new Clip(job, 0, List.of()));
        Clip bad = clipRepository.save(new Clip(job, 1, List.of()));
        Task okTask = runningRender(ok, worker);
        Task badTask = runningRender(bad, worker);
        assertThat(clipState(ok)).as("accepted render").isEqualTo(ClipState.RENDERING);

        lifecycle.done(okTask.getId(), okTask.getAttempt(), worker.getId());
        assertThat(clipState(ok)).isEqualTo(ClipState.RENDERED);
        assertThat(jobState()).as("one clip still rendering").isEqualTo(VideoJobState.RENDERING);

        // The other clip runs out of attempts
        // (each failure sends it back to the queue: it's handed to the worker again first)
        for (int i = 0; i < TaskLifecycleService.MAX_FAILURES; i++) {
            Task current = taskRepository.findById(badTask.getId()).orElseThrow();
            if (current.getAssignedWorker() == null) {
                current.assignTo(worker);
                current = taskRepository.save(current);
            }
            lifecycle.fail(current.getId(), current.getAttempt(), "boom", worker.getId());
        }
        assertThat(clipState(bad)).isEqualTo(ClipState.FAILED);
        assertThat(jobState()).isEqualTo(VideoJobState.RENDERED);

        // Retrying the failed clip sends the job back to RENDERING so it gets dispatched
        assertThat(jobControl.retryFailedTasks(job.getId())).isEqualTo(1);
        assertThat(clipState(bad)).isEqualTo(ClipState.PLANNED);
        assertThat(jobState()).isEqualTo(VideoJobState.RENDERING);
        assertThat(taskRepository.findById(badTask.getId()).orElseThrow().getState())
                .isEqualTo(TaskState.PENDING);
    }

    @Test
    void migration_unsticks_jobs_rendered_before_the_rendered_state_existed() throws Exception {
        job = renderingJob();
        Clip done = clipRepository.save(new Clip(job, 0, List.of()));
        Clip failed = clipRepository.save(new Clip(job, 1, List.of()));
        jdbc.update("UPDATE clip SET state = 'RENDERED' WHERE id = ?", done.getId());
        Task failedTask = Task.forRenderClip(failed, Map.of());
        failedTask.recordFailure(1);
        taskRepository.save(failedTask);

        String sql = Files.readString(Path.of("src/main/resources/db/migration/V16__mark_finished_renders.sql"),
                StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) {
            String stripped = statement.lines().filter(l -> !l.trim().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b).trim();
            if (!stripped.isEmpty()) jdbc.execute(stripped);
        }

        assertThat(clipState(failed)).isEqualTo(ClipState.FAILED);
        assertThat(jobState()).isEqualTo(VideoJobState.RENDERED);
    }

    @Test
    void rendered_clip_can_be_watched_with_seeking_and_downloaded() throws Exception {
        job = renderingJob();
        Clip clip = clipRepository.save(new Clip(job, 2, List.of()));
        Path file = files.renderedClip(job.getId(), 2);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[4096]);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        String url = "/admin/jobs/" + job.getId() + "/clips/" + clip.getId() + "/video";

        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("Content-Disposition", containsString("clip-3-")));

        // Byte ranges let the browser's player seek
        mvc.perform(get(url).header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-99/4096"));

        mvc.perform(get(url).param("download", "true"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));

        // A clip id under another job's URL is not served
        mvc.perform(get("/admin/jobs/" + UUID.randomUUID() + "/clips/" + clip.getId() + "/video"))
                .andExpect(status().isNotFound());
    }
}
