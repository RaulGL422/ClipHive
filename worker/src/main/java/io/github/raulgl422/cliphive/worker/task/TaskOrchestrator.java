package io.github.raulgl422.cliphive.worker.task;

import io.github.raulgl422.cliphive.protocol.message.Progress;
import io.github.raulgl422.cliphive.protocol.message.ProtocolMessage;
import io.github.raulgl422.cliphive.protocol.message.TaskAccept;
import io.github.raulgl422.cliphive.protocol.message.TaskAssign;
import io.github.raulgl422.cliphive.protocol.message.TaskRelease;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import io.github.raulgl422.cliphive.worker.net.ServerConnection;
import io.github.raulgl422.cliphive.worker.runtime.WorkerController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Receives tasks from the server and dispatches them to type-specific
 * handlers (docs/DESIGN.md §8, §9).
 *
 * <p>One virtual thread per task keeps the implementation simple while
 * preserving the worker's capacity limit (enforced on the server side
 * via {@code maxConcurrentTasks}).
 */
public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);

    private final TranscribeHandler transcribeHandler;
    private final RenderHandler renderHandler;
    private final WorkerController controller;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID, ActiveTask> activeTasks = new ConcurrentHashMap<>();

    /** When true, incoming TASK_ASSIGN messages are released immediately (docs/DESIGN.md §8.3). */
    private volatile boolean paused = false;

    public TaskOrchestrator(TranscribeHandler transcribeHandler,
                            RenderHandler renderHandler,
                            WorkerController controller) {
        this.transcribeHandler = transcribeHandler;
        this.renderHandler = renderHandler;
        this.controller = controller;
    }

    /**
     * Sees every message the worker sends ({@link ServerConnection#setOutgoingListener})
     * and turns task PROGRESS into a status line for the UI and the log.
     */
    public void onOutgoing(ProtocolMessage message) {
        if (message instanceof Progress p) {
            ActiveTask task = activeTasks.get(p.taskId());
            String type = task != null ? typeLabel(task.assign().type()) : "Tarea";
            String description = type + " · " + phaseLabel(p.phase());
            int previous = task != null ? task.getLastPercent() : -1;
            if (task != null) task.setLastPercent(p.percent());
            // Log every 10 % so the window/file log shows it moving without flooding.
            if (previous < 0 || p.percent() / 10 > previous / 10) {
                log.info("{}: {} %", description, p.percent());
            }
            controller.onTaskProgress(description, p.percent());
        }
    }

    /** Pauses or resumes acceptance of new tasks (docs/DESIGN.md §8.3). */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * Called when the server sends TASK_ASSIGN. Sends TASK_ACCEPT immediately
     * and starts processing in a virtual thread. If paused, releases the task
     * back to the server instead.
     */
    public void onTaskAssign(TaskAssign assign, ServerConnection conn) {
        if (paused) {
            log.info("Paused — releasing TASK_ASSIGN {} back to server", assign.taskId());
            conn.sendMessage(new TaskRelease(assign.taskId(), assign.attempt()));
            return;
        }

        log.info("TASK_ASSIGN received: {} type={} attempt={}",
                 assign.taskId(), assign.type(), assign.attempt());

        ActiveTask task = new ActiveTask(assign);
        ActiveTask superseded = activeTasks.put(assign.taskId(), task);
        if (superseded != null) {
            // The server gave this task out again (a new attempt): the old run's
            // result would be refused anyway, so stop it instead of running both.
            log.info("Task {} reassigned (attempt {} → {}): stopping the old run",
                    assign.taskId(), superseded.assign().attempt(), assign.attempt());
            superseded.cancel();
        }
        try {
            conn.sendMessage(new TaskAccept(assign.taskId(), assign.attempt()));
            controller.onTaskStarted();
            controller.onTaskProgress(typeLabel(assign.type()) + " · empezando", -1);
            task.setFuture(submit(task, conn));
        } catch (RuntimeException e) {
            // Not started after all: forget it (the caller tells the server)
            activeTasks.remove(assign.taskId(), task);
            controller.onTaskFinished(activeTasks.size());
            throw e;
        }
    }

    private Future<?> submit(ActiveTask task, ServerConnection conn) {
        TaskAssign assign = task.assign();
        // The handlers report their own failures (TASK_FAILED); this catch only keeps
        // anything that still escapes from vanishing inside the Future.
        return executor.submit(() -> {
            try {
                runTask(assign, conn);
            } catch (RuntimeException | Error e) {
                log.error("Task {} failed unexpectedly: {}", assign.taskId(), e.getMessage(), e);
            } finally {
                // Only this run: a newer attempt of the same task may have taken the slot
                activeTasks.remove(assign.taskId(), task);
                controller.onTaskFinished(activeTasks.size());
            }
        });
    }

    /** Called on TASK_CANCEL from the server. */
    public void onTaskCancel(UUID taskId) {
        ActiveTask task = activeTasks.remove(taskId);
        if (task != null) {
            log.info("Cancelling task {}", taskId);
            task.cancel();
        }
    }

    /**
     * Cancels all active tasks before shutdown. The GOODBYE that follows makes
     * the server put them back in the queue without spending an attempt
     * (docs/DESIGN.md §6.5, §8.2 — clean stop).
     */
    public void releaseAll() {
        activeTasks.forEach((id, task) -> {
            log.info("Releasing task {} before shutdown", id);
            task.cancel();
        });
        activeTasks.clear();
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private static String typeLabel(TaskType type) {
        return switch (type) {
            case TRANSCRIBE  -> "Transcribiendo";
            case RENDER_CLIP -> "Renderizando clip";
        };
    }

    private static String phaseLabel(String phase) {
        if (phase == null) return "";
        return switch (phase) {
            case "downloading"  -> "descargando";
            case "transcribing" -> "whisper";
            case "uploading"    -> "subiendo resultado";
            case "subtitles"    -> "subtítulos";
            case "rendering"    -> "ffmpeg";
            default             -> phase;
        };
    }

    /** Exhaustive on purpose: a new {@link TaskType} won't compile until it has a handler. */
    private void runTask(TaskAssign assign, ServerConnection conn) {
        switch (assign.type()) {
            case TRANSCRIBE  -> transcribeHandler.handle(assign, conn);
            case RENDER_CLIP -> renderHandler.handle(assign, conn);
        }
    }
}
