package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.message.TaskCancel;
import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.worker.ConnectedWorker;
import io.github.raulgl422.cliphive.server.service.worker.WorkerSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Superadmin actions on a job — cancel, retry its failed tasks, relaunch it
 * (DESIGN.md §12.2, §5.3).
 */
@Service
public class JobControlService {

    private static final Logger log = LoggerFactory.getLogger(JobControlService.class);
    private static final String COMPONENT = "trabajos";

    private static final Set<TaskState> CANCELLABLE_TASKS =
            EnumSet.of(TaskState.PENDING, TaskState.ASSIGNED, TaskState.RUNNING);
    private static final Set<ClipState> CANCELLABLE_CLIPS =
            EnumSet.of(ClipState.PLANNED, ClipState.RENDERING, ClipState.RENDERED, ClipState.SCHEDULED);

    private final VideoJobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final ClipRepository clipRepository;
    private final ScheduledPostRepository postRepository;
    private final VideoJobService jobService;
    private final WorkerSessionRegistry workerRegistry;
    private final LogEventService logEvents;
    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbc;
    private final JobFiles files;

    public JobControlService(VideoJobRepository jobRepository,
                             TaskRepository taskRepository,
                             ClipRepository clipRepository,
                             ScheduledPostRepository postRepository,
                             VideoJobService jobService,
                             WorkerSessionRegistry workerRegistry,
                             LogEventService logEvents,
                             TransactionTemplate txTemplate,
                             JdbcTemplate jdbc,
                             JobFiles files) {
        this.jdbc = jdbc;
        this.files = files;
        this.jobRepository = jobRepository;
        this.taskRepository = taskRepository;
        this.clipRepository = clipRepository;
        this.postRepository = postRepository;
        this.jobService = jobService;
        this.workerRegistry = workerRegistry;
        this.logEvents = logEvents;
        this.txTemplate = txTemplate;
    }

    /** Why a job can't be deleted right now; see {@link #delete}. */
    public static class DeleteRefusedException extends RuntimeException {
        public DeleteRefusedException(String message) {
            super(message);
        }
    }

    /**
     * Deletes a job for good (DESIGN.md §12.2): its rows — clips, tasks,
     * publications with their stats, transcript and analysis — and its folder
     * on disk. A running job is cancelled first. Logs are kept.
     *
     * @return false if the job doesn't exist
     * @throws DeleteRefusedException while one of its clips is being uploaded
     */
    public boolean delete(UUID jobId) {
        VideoJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return false;
        boolean uploading = postRepository.findByClipVideoJobId(jobId).stream().anyMatch(p ->
                p.getState() == ScheduledPostState.UPLOADING
                        || p.getState() == ScheduledPostState.PROCESSING);
        if (uploading) {
            throw new DeleteRefusedException("Hay un clip subiéndose ahora mismo: espera a que termine y vuelve a intentarlo.");
        }
        if (!job.isTerminal()) cancel(jobId); // tells the workers to stop its tasks

        String title = job.getTitle() != null ? job.getTitle() : job.getSourceUrl();
        txTemplate.executeWithoutResult(status -> {
            String clips = "SELECT id FROM clip WHERE video_job_id = ?";
            jdbc.update("DELETE FROM post_stats WHERE scheduled_post_id IN "
                    + "(SELECT id FROM scheduled_post WHERE clip_id IN (" + clips + "))", jobId);
            jdbc.update("DELETE FROM scheduled_post WHERE clip_id IN (" + clips + ")", jobId);
            jdbc.update("DELETE FROM task WHERE video_job_id = ? OR clip_id IN (" + clips + ")", jobId, jobId);
            jdbc.update("DELETE FROM clip WHERE video_job_id = ?", jobId);
            jdbc.update("DELETE FROM transcript WHERE video_job_id = ?", jobId);
            jdbc.update("DELETE FROM content_analysis WHERE video_job_id = ?", jobId);
            jdbc.update("DELETE FROM video_job WHERE id = ?", jobId);
        });

        // Files after the rows are gone: a half-deleted folder is harmless, a dangling row isn't
        Path dir = files.jobDir(jobId);
        try {
            FileSystemUtils.deleteRecursively(dir);
        } catch (IOException e) {
            log.warn("Job {} deleted but its folder {} couldn't be removed: {}", jobId, dir, e.getMessage());
        }
        log.info("Job {} deleted ({})", jobId, title);
        logEvents.info(COMPONENT, "Trabajo eliminado: " + title);
        return true;
    }

    /**
     * Cancels a job that hasn't finished: the job, its pending/running tasks
     * and its unfinished clips go to CANCELLED, and workers holding one of its
     * tasks get TASK_CANCEL.
     *
     * @return false if the job doesn't exist or had already finished
     */
    public boolean cancel(UUID jobId) {
        record Held(UUID workerId, UUID taskId) {}
        List<Held> held = new ArrayList<>();

        Boolean cancelled = txTemplate.execute(status -> {
            VideoJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null || job.isTerminal()) return false;

            job.cancel();
            jobRepository.save(job);

            int tasks = 0;
            for (Task task : taskRepository.findAllForJob(jobId)) {
                if (!CANCELLABLE_TASKS.contains(task.getState())) continue;
                if (task.getAssignedWorker() != null) {
                    held.add(new Held(task.getAssignedWorker().getId(), task.getId()));
                }
                task.cancel();
                taskRepository.save(task);
                tasks++;
            }
            // Publications that haven't started uploading; the ones already at the
            // platform are left alone (they can't be pulled back from here).
            for (ScheduledPost post : postRepository.findByClipVideoJobId(jobId)) {
                if (post.getState() == ScheduledPostState.SCHEDULED) {
                    post.setState(ScheduledPostState.CANCELLED);
                    postRepository.save(post);
                }
            }
            for (Clip clip : clipRepository.findByVideoJobIdOrderByClipIndex(jobId)) {
                if (CANCELLABLE_CLIPS.contains(clip.getState())) {
                    clip.setState(ClipState.CANCELLED);
                    clipRepository.save(clip);
                }
            }
            log.info("Job {} cancelled ({} task(s) cancelled)", jobId, tasks);
            logEvents.info(COMPONENT, "Trabajo cancelado (" + tasks + " tarea(s) canceladas)",
                    jobId.toString());
            return true;
        });

        // After commit: tell the workers to stop. Whatever they send back now
        // carries an old attempt and is ignored.
        for (Held h : held) {
            ConnectedWorker cw = workerRegistry.byWorkerId(h.workerId());
            if (cw != null) cw.send(new TaskCancel(h.taskId()));
        }
        return Boolean.TRUE.equals(cancelled);
    }

    /**
     * Puts the FAILED tasks of a job that's still in progress back in the
     * queue, with fresh retry counters.
     *
     * @return how many tasks were retried (0 if none, or the job had finished)
     */
    public int retryFailedTasks(UUID jobId) {
        Integer retried = txTemplate.execute(status -> {
            VideoJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null || job.isTerminal()) return 0;

            int count = 0;
            boolean renderRetried = false;
            for (Task task : taskRepository.findAllForJob(jobId)) {
                if (task.getState() != TaskState.FAILED) continue;
                task.retry();
                taskRepository.save(task);
                if (task.getClip() != null && task.getClip().getState() == ClipState.FAILED) {
                    task.getClip().setState(ClipState.PLANNED);
                    clipRepository.save(task.getClip());
                    renderRetried = true;
                }
                count++;
            }
            // A finished render with a clip being retried is rendering again —
            // render tasks are only dispatched while the job is RENDERING.
            if (renderRetried && job.getState() == VideoJobState.RENDERED) {
                job.startRendering();
                jobRepository.save(job);
            }
            if (count > 0) {
                log.info("Job {}: {} failed task(s) back in the queue", jobId, count);
                logEvents.info(COMPONENT, count + " tarea(s) fallida(s) reintentadas", jobId.toString());
            }
            return count;
        });
        return retried != null ? retried : 0;
    }

    /**
     * Submits a new job with the same link and options as a finished one;
     * the old job stays as history.
     *
     * @return the new job, or null if the original doesn't exist or isn't finished
     */
    public VideoJob relaunch(UUID jobId) {
        VideoJob old = jobRepository.findById(jobId).orElse(null);
        if (old == null || !old.isTerminal()) return null;
        VideoJob fresh = jobService.submit(old.getSourceUrl(), old.getLanguage(),
                old.getFpsMode(), old.isReviewMode(), old.getClipMode(), old.getAudioLanguage());
        log.info("Job {} relaunched as {}", jobId, fresh.getId());
        logEvents.info(COMPONENT, "Trabajo relanzado como " + fresh.getId(), jobId.toString());
        return fresh;
    }
}
