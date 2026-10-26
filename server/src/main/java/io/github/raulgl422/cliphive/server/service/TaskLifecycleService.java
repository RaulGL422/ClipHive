package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Handles task state transitions driven by worker messages and by the
 * heartbeat scheduler (docs/DESIGN.md §6.3, §6.5, §7).
 *
 * <p>All public methods are {@link Transactional}: they run in a single
 * transaction so the DB is never left in an inconsistent state if two
 * events race (e.g., a TASK_DONE arrives while HeartbeatScheduler is
 * reassigning the same task for a missed PONG).
 *
 * <p><b>Fencing token</b>: every method checks that the incoming
 * {@code attempt} matches the current row. A stale {@code attempt}
 * (from a worker that was presumed dead and reconnects late) is silently
 * ignored — the task has already been reassigned with a higher attempt
 * number (docs/DESIGN.md §6.3).
 */
@Service
public class TaskLifecycleService {

    static final int MAX_FAILURES = 3;
    static final int MAX_DISCONNECTS = 10;

    /** A worker holds the task: what a disconnect, restart or stall takes back. */
    private static final List<TaskState> ACTIVE = List.of(TaskState.ASSIGNED, TaskState.RUNNING);

    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleService.class);

    private final TaskRepository taskRepository;
    private final VideoJobRepository jobRepository;
    private final ClipRepository clipRepository;
    private final LogEventService logEvents;

    public TaskLifecycleService(TaskRepository taskRepository,
                                VideoJobRepository jobRepository,
                                ClipRepository clipRepository,
                                LogEventService logEvents) {
        this.taskRepository = taskRepository;
        this.jobRepository = jobRepository;
        this.clipRepository = clipRepository;
        this.logEvents = logEvents;
    }

    /** Worker accepted the task: ASSIGNED → RUNNING. */
    @Transactional
    public void accept(UUID taskId, int attempt, UUID workerId) {
        Task task = load(taskId);
        if (task == null || !checkAttempt(task, attempt, workerId, "TASK_ACCEPT")) return;
        task.setState(TaskState.RUNNING);
        taskRepository.save(task);
        if (task.getType() == TaskType.RENDER_CLIP && task.getClip() != null) {
            Clip clip = task.getClip();
            clip.setState(ClipState.RENDERING);
            clipRepository.save(clip);
        }
        log.info("Task {} accepted — {} running on worker {}", taskId, task.getType(), workerName(task));
        logEvents.info(COMPONENT, describe(task) + " empezada en el worker " + workerName(task), jobId(task));
    }

    /**
     * Worker rejected the task: goes back to PENDING with attempt bumped.
     * Counts as a failure: the task gave the worker a problem, not a connection
     * drop (our reading of §6.3, which doesn't name TASK_REJECT).
     */
    @Transactional
    public void reject(UUID taskId, int attempt, String reason, UUID workerId) {
        Task task = load(taskId);
        if (task == null || !checkAttempt(task, attempt, workerId, "TASK_REJECT")) return;
        log.info("Task {} rejected by worker (attempt {}): {}", taskId, attempt, reason);
        task.recordFailure(MAX_FAILURES);
        taskRepository.save(task);
        logEvents.warn(COMPONENT, describe(task) + " rechazada por el worker: " + reason, jobId(task));
        if (task.getState() == TaskState.FAILED) {
            log.warn("Task {} permanently failed after {} rejections", taskId, MAX_FAILURES);
            logEvents.error(COMPONENT, describe(task) + " fallida definitivamente tras "
                    + MAX_FAILURES + " rechazos", jobId(task));
            onPermanentFailure(task);
        }
    }

    /** Worker reported progress. */
    @Transactional
    public void progress(UUID taskId, int attempt, int percent, String phase, UUID workerId) {
        Task task = load(taskId);
        if (task == null || !checkAttempt(task, attempt, workerId, "PROGRESS")) return;
        String previousPhase = task.getLastProgressPhase();
        Integer previousPercent = task.getLastProgressPercent();
        task.recordProgress(percent, phase);
        taskRepository.save(task);

        // Log milestones only (phase change or every 25 %), not every PROGRESS
        // message — whisper reports progress every few percent.
        boolean phaseChanged = !Objects.equals(previousPhase, phase);
        boolean milestone = previousPercent == null || percent / 25 > previousPercent / 25;
        if (phaseChanged || milestone) {
            log.info("Task {} {} — {} {}%", taskId, task.getType(), phase, percent);
            logEvents.info(COMPONENT, describe(task) + ": " + phaseLabel(phase) + " " + percent + " %",
                    jobId(task));
        } else {
            log.debug("Task {} progress {} {}%", taskId, phase, percent);
        }
    }

    /**
     * Worker completed the task: DONE.
     * For TRANSCRIBE tasks, checks if all chunks are done → transitions the
     * VideoJob to ANALYZING.
     */
    @Transactional
    public void done(UUID taskId, int attempt, UUID workerId) {
        Task task = load(taskId);
        if (task == null || !checkAttempt(task, attempt, workerId, "TASK_DONE")) return;

        task.setState(TaskState.DONE);
        taskRepository.save(task);
        log.info("Task {} DONE (type={}, attempt={})", taskId, task.getType(), attempt);
        logEvents.info(COMPONENT, describe(task) + " terminada", jobId(task));

        if (task.getType() == TaskType.TRANSCRIBE && task.getVideoJob() != null) {
            maybeAdvanceJobToAnalyzing(task.getVideoJob().getId());
        } else if (task.getType() == TaskType.RENDER_CLIP && task.getClip() != null) {
            markClipRendered(task.getClip().getId());
        }
    }

    /** Worker reported TASK_FAILED: one failure toward the max-3 budget (§6.3); FAILED only when it's spent. */
    @Transactional
    public void fail(UUID taskId, int attempt, String error, UUID workerId) {
        Task task = load(taskId);
        if (task == null || !checkAttempt(task, attempt, workerId, "TASK_FAILED")) return;
        log.warn("Task {} failed (attempt {}): {}", taskId, attempt, error);
        task.recordFailure(MAX_FAILURES);
        taskRepository.save(task);
        if (task.getState() == TaskState.FAILED) {
            log.error("Task {} permanently failed after {} failures", taskId, MAX_FAILURES);
            logEvents.error(COMPONENT, describe(task) + " fallida definitivamente: " + error, jobId(task));
            onPermanentFailure(task);
        } else {
            logEvents.warn(COMPONENT, describe(task) + " falló (se reintentará): " + error, jobId(task));
        }
    }

    /**
     * Worker voluntarily released the task: back to PENDING.
     * Does not count against either retry counter (docs/DESIGN.md §6.3).
     */
    @Transactional
    public void release(UUID taskId, int attempt, UUID workerId) {
        Task task = load(taskId);
        if (task == null || !checkAttempt(task, attempt, workerId, "TASK_RELEASE")) return;
        task.release();
        taskRepository.save(task);
        log.info("Task {} released (attempt {} → {})", taskId, attempt, task.getAttempt());
        logEvents.info(COMPONENT, describe(task) + " devuelta a la cola por el worker", jobId(task));
    }

    /**
     * Reassigns all active tasks of a worker that has disconnected (a lost
     * connection spends the separate max-10 budget, §6.3). Called by
     * {@link io.github.raulgl422.cliphive.server.service.worker.HeartbeatScheduler} and
     * when a session closes or is replaced by a reconnect.
     */
    @Transactional
    public void reassignWorkerTasks(UUID workerId) {
        requeue(taskRepository.findByAssignedWorkerIdAndStateIn(workerId, ACTIVE),
                t -> t.recordDisconnect(MAX_DISCONNECTS),
                "el worker se desconectó", "demasiadas desconexiones");
    }

    /**
     * GOODBYE with tasks still held (§6.5): the worker is stopping on purpose,
     * so its tasks go back to the queue without spending a counter, as with
     * {@code TASK_RELEASE}.
     */
    @Transactional
    public void releaseWorkerTasks(UUID workerId) {
        requeue(taskRepository.findByAssignedWorkerIdAndStateIn(workerId, ACTIVE),
                Task::release, "el worker se detuvo", null);
    }

    /**
     * Tasks with no news since {@code cutoff} (§6.5 progress timeout), as
     * task id → the worker holding them, so the caller can tell each worker
     * to drop its task <em>before</em> {@link #reassignStalledTasks} puts it
     * back in the queue (it could be dispatched to the same worker again).
     */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> findStalledTasks(Instant cutoff) {
        Map<UUID, UUID> workers = new HashMap<>();
        for (Task t : taskRepository.findByStateInAndUpdatedAtBefore(ACTIVE, cutoff)) {
            UUID worker = t.getAssignedWorker() != null ? t.getAssignedWorker().getId() : null;
            if (worker != null) workers.put(t.getId(), worker);
        }
        return workers;
    }

    /**
     * Progress timeout (§6.5): a task with no news since {@code cutoff} is
     * stuck even if its worker answers the pings. It counts as a failure
     * (§6.3) and goes back to the queue.
     */
    @Transactional
    public int reassignStalledTasks(Instant cutoff, Duration timeout) {
        List<Task> stalled = taskRepository.findByStateInAndUpdatedAtBefore(ACTIVE, cutoff);
        requeue(stalled, t -> t.recordFailure(MAX_FAILURES),
                "sin progreso en " + timeout.toMinutes() + " min", "demasiados fallos");
        return stalled.size();
    }

    /**
     * Applies {@code how} (which bumps the attempt) to each task, logs where
     * it went and, when that used up its budget, fails it for good.
     */
    private void requeue(List<Task> tasks, Consumer<Task> how, String why, String exhausted) {
        for (Task task : tasks) {
            how.accept(task);
            taskRepository.save(task);
            log.info("Task {} back to {} ({}), attempt {}", task.getId(), task.getState(), why, task.getAttempt());
            if (task.getState() == TaskState.FAILED) {
                logEvents.error(COMPONENT, describe(task) + " fallida definitivamente: " + exhausted, jobId(task));
                onPermanentFailure(task);
            } else {
                logEvents.warn(COMPONENT, describe(task) + " vuelve a la cola: " + why, jobId(task));
            }
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private static final Set<ClipState> FINAL_CLIP_STATES =
            EnumSet.of(ClipState.RENDERED, ClipState.FAILED, ClipState.CANCELLED);

    /** Component name for this service's entries in the superadmin log. */
    private static final String COMPONENT = "tareas";

    /** e.g. "TRANSCRIBE 3f2a9c1e" — short enough for a log line. */
    private static String describe(Task task) {
        return task.getType() + " " + task.getId().toString().substring(0, 8);
    }

    private static String jobId(Task task) {
        if (task.getVideoJob() != null) return task.getVideoJob().getId().toString();
        if (task.getClip() != null && task.getClip().getVideoJob() != null) {
            return task.getClip().getVideoJob().getId().toString();
        }
        return null;
    }

    private static String workerName(Task task) {
        return task.getAssignedWorker() != null ? task.getAssignedWorker().getName() : "?";
    }

    private static String phaseLabel(String phase) {
        if (phase == null) return "";
        return switch (phase) {
            case "downloading"  -> "descargando";
            case "transcribing" -> "transcribiendo";
            case "uploading"    -> "subiendo resultado";
            case "subtitles"    -> "generando subtítulos";
            case "rendering"    -> "renderizando";
            default             -> phase;
        };
    }

    private Task load(UUID taskId) {
        return taskRepository.findById(taskId).orElse(null);
    }

    /**
     * The message is about the task's current attempt and comes from the worker
     * that holds it (§6.3, §13): a worker can't act on another worker's task.
     */
    private boolean checkAttempt(Task task, int incoming, UUID workerId, String msgType) {
        if (task.getAttempt() != incoming) {
            log.warn("Stale {} for task {} (expected attempt {}, got {}); ignoring",
                     msgType, task.getId(), task.getAttempt(), incoming);
            return false;
        }
        UUID holder = task.getAssignedWorker() != null ? task.getAssignedWorker().getId() : null;
        if (!Objects.equals(holder, workerId)) {
            log.warn("{} for task {} from worker {}, but it's assigned to {}; ignoring",
                     msgType, task.getId(), workerId, holder);
            return false;
        }
        return true;
    }

    /**
     * Marks the clip as RENDERED and checks whether that finishes the job's
     * render (see {@link #maybeFinishRendering}).
     */
    private void markClipRendered(UUID clipId) {
        Clip clip = clipRepository.findById(clipId).orElse(null);
        if (clip == null) return;
        clip.setState(ClipState.RENDERED);
        clipRepository.save(clip);
        log.info("Clip {} RENDERED", clipId);

        maybeFinishRendering(clip.getVideoJob().getId());
    }

    /** A RENDER_CLIP ran out of attempts: its clip is FAILED, which may end the job's render. */
    private void onPermanentFailure(Task task) {
        if (task.getType() != TaskType.RENDER_CLIP || task.getClip() == null) return;
        Clip clip = task.getClip();
        clip.setState(ClipState.FAILED);
        clipRepository.save(clip);
        maybeFinishRendering(clip.getVideoJob().getId());
    }

    /**
     * RENDERING → RENDERED once every clip is final — rendered, failed or
     * cancelled (DESIGN.md §5.1). The publication planner takes it from there.
     */
    private void maybeFinishRendering(UUID jobId) {
        int total = clipRepository.countByVideoJobId(jobId);
        int finished = clipRepository.countByVideoJobIdAndStateIn(jobId, FINAL_CLIP_STATES);
        if (total == 0 || finished < total) return;
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getState() != VideoJobState.RENDERING) return;
            job.finishRendering();
            jobRepository.save(job);
            int rendered = clipRepository.countByVideoJobIdAndState(jobId, ClipState.RENDERED);
            log.info("Job {} → RENDERED ({}/{} clip(s) rendered)", jobId, rendered, total);
            logEvents.info("trabajos", "Render terminado: " + rendered + " de " + total
                    + " clip(s) listos", jobId.toString());
        });
    }

    /**
     * If every TRANSCRIBE task for the given job is DONE, transitions the
     * VideoJob to ANALYZING (docs/DESIGN.md §4, step 6 entry condition).
     */
    private void maybeAdvanceJobToAnalyzing(UUID jobId) {
        int total = taskRepository.countByVideoJobId(jobId);
        int done  = taskRepository.countByVideoJobIdAndState(jobId, TaskState.DONE);
        if (total > 0 && done == total) {
            jobRepository.findById(jobId).ifPresent(job -> {
                if (job.getState() == VideoJobState.TRANSCRIBING) {
                    job.setState(VideoJobState.ANALYZING);
                    jobRepository.save(job);
                    log.info("Job {} → ANALYZING ({}/{} chunks done)", jobId, done, total);
                }
            });
        }
    }
}
