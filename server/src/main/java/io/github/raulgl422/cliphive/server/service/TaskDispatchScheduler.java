package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.message.TaskAssign;
import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.Worker;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.service.worker.ConnectedWorker;
import io.github.raulgl422.cliphive.server.service.worker.WorkerSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodically dispatches PENDING tasks to workers that have free capacity
 * (docs/DESIGN.md §6.3, §6.4).
 *
 * <p><b>Atomic assignment</b>: tasks are locked with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} inside a transaction so two
 * concurrent dispatch cycles can never hand the same task to two workers.
 *
 * <p><b>Capacity matching</b>: each worker declares a {@code maxConcurrency}
 * in its {@code HELLO} capabilities. The scheduler only assigns up to
 * {@code maxConcurrency - activeTaskCount} new tasks per worker per tick.
 *
 * <p><b>File URLs</b>: {@code TaskAssign} carries signed download + upload
 * URLs tied to {@code (taskId, attempt)} so a stale worker that reconnects
 * late cannot access task files after reassignment.
 */
@Component
public class TaskDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchScheduler.class);

    /** Dispatch tick — fast enough to feel responsive, slow enough not to hammer the DB. */
    static final long DISPATCH_INTERVAL_MS = 3_000L;

    private final WorkerSessionRegistry registry;
    private final TaskRepository taskRepository;
    private final SignedUrlService signedUrlService;
    private final TransactionTemplate txTemplate;
    private final ClipHiveProperties props;
    private final ClipRepository clipRepository;
    private final SettingService settings;

    public TaskDispatchScheduler(WorkerSessionRegistry registry,
                                 TaskRepository taskRepository,
                                 SignedUrlService signedUrlService,
                                 TransactionTemplate txTemplate,
                                 ClipHiveProperties props,
                                 ClipRepository clipRepository,
                                 SettingService settings) {
        this.clipRepository = clipRepository;
        this.settings = settings;
        this.registry = registry;
        this.taskRepository = taskRepository;
        this.signedUrlService = signedUrlService;
        this.txTemplate = txTemplate;
        this.props = props;
    }

    @Scheduled(fixedDelay = DISPATCH_INTERVAL_MS)
    public void dispatch() {
        Collection<ConnectedWorker> workers = registry.all();
        if (workers.isEmpty()) return;

        for (ConnectedWorker cw : workers) {
            Worker worker = cw.worker();
            if (worker.getLastCapabilities() == null) continue;

            int maxConcurrency = worker.getLastCapabilities().maxConcurrentTasks();
            int active = taskRepository.countByAssignedWorkerIdAndStateIn(
                    cw.workerId(), List.of(TaskState.ASSIGNED, TaskState.RUNNING));
            int slots = maxConcurrency - active;
            if (slots <= 0) continue;

            List<Task> assigned = assignTasks(cw, slots);
            for (Task task : assigned) {
                boolean sent;
                try {
                    sent = cw.send(buildTaskAssign(task));
                } catch (RuntimeException e) {
                    log.warn("Could not build TaskAssign for task {}: {}", task.getId(), e.getMessage());
                    sent = false;
                }
                if (!sent) unassign(task, cw);
            }
        }
    }

    /**
     * The TASK_ASSIGN never reached the worker: back to the queue at once
     * (new attempt, no counter spent), instead of sitting ASSIGNED until the
     * progress timeout.
     */
    private void unassign(Task task, ConnectedWorker cw) {
        log.warn("TaskAssign for task {} didn't reach worker {}; back to the queue", task.getId(), cw.workerId());
        txTemplate.executeWithoutResult(status -> taskRepository.findById(task.getId())
                .filter(t -> t.getState() == TaskState.ASSIGNED && t.getAttempt() == task.getAttempt())
                .ifPresent(t -> {
                    t.release();
                    taskRepository.save(t);
                }));
    }

    /** Locks and assigns up to {@code slots} PENDING tasks to {@code cw} in one transaction. */
    private List<Task> assignTasks(ConnectedWorker cw, int slots) {
        List<Task> result = new ArrayList<>();
        txTemplate.executeWithoutResult(status -> {
            List<Task> candidates = taskRepository.lockNextPending(slots);
            for (Task task : candidates) {
                task.assignTo(cw.worker());
                taskRepository.save(task);
                result.add(task);
                log.debug("Task {} assigned to worker {} (attempt {})",
                          task.getId(), cw.workerId(), task.getAttempt());
            }
        });
        return result;
    }

    private TaskAssign buildTaskAssign(Task task) {
        String baseUrl = workerBaseUrl();
        int attempt = task.getAttempt();

        Map<String, String> downloadUrls;
        Map<String, String> uploadUrls;

        if (task.getType() == TaskType.TRANSCRIBE) {
            String audioPath = "/tasks/" + task.getId() + "/" + attempt + "/audio";
            String txPath    = "/tasks/" + task.getId() + "/" + attempt + "/transcript";
            downloadUrls = Map.of("audioChunk",
                    signedUrlService.generateWorkerUrl(baseUrl, task.getId(), attempt, audioPath));
            uploadUrls = Map.of("transcript",
                    signedUrlService.generateWorkerUrl(baseUrl, task.getId(), attempt, txPath));
        } else {
            // RENDER_CLIP: source video download + rendered clip upload
            String sourcePath = "/tasks/" + task.getId() + "/" + attempt + "/source";
            String clipPath   = "/tasks/" + task.getId() + "/" + attempt + "/clip";
            downloadUrls = Map.of("sourceClip",
                    signedUrlService.generateWorkerUrl(baseUrl, task.getId(), attempt, sourcePath));
            uploadUrls = Map.of("renderedClip",
                    signedUrlService.generateWorkerUrl(baseUrl, task.getId(), attempt, clipPath));
        }

        return new TaskAssign(task.getId(), attempt, task.getType(),
                              renderParameters(task), downloadUrls, uploadUrls);
    }

    /**
     * Render tasks also carry the clip's on-screen title, read at dispatch so
     * it's the latest one (DESIGN.md §9.2, §10.3).
     */
    private Map<String, Object> renderParameters(Task task) {
        Map<String, Object> params = task.getParametersJson();
        if (task.getType() != TaskType.RENDER_CLIP || task.getClip() == null
                || clipRepository == null || settings == null || !settings.overlayEnabled()) {
            return params;
        }
        String overlay = clipRepository.findById(task.getClip().getId())
                .map(Clip::getOverlayTitle).orElse(null);
        if (overlay == null || overlay.isBlank()) return params;
        Map<String, Object> withOverlay = new LinkedHashMap<>(params);
        withOverlay.put("overlayTitle", overlay);
        return withOverlay;
    }

    /** Builds the base URL for the worker-facing HTTP connector. */
    String workerBaseUrl() {
        String bind = props.getWorkerBind();
        if (bind == null || bind.isBlank()) bind = "127.0.0.1";
        return "http://" + bind + ":" + props.getWorkerPort();
    }
}
