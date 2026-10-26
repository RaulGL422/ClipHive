package io.github.raulgl422.cliphive.worker.runtime;

import io.github.raulgl422.cliphive.worker.net.ServerConnection;
import io.github.raulgl422.cliphive.worker.task.TaskOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Central state machine for the worker — bridges the UI (JavaFX or headless)
 * and the network/task layers (docs/DESIGN.md §8.3).
 *
 * <p>The UI calls {@link #pause()}, {@link #resume()}, {@link #stop()} here,
 * and subscribes to state changes via {@link #addStateListener(Consumer)}.
 * The connection and orchestrator are wired in after construction via
 * {@link #wire(ServerConnection, TaskOrchestrator)}.
 */
public class WorkerController {

    private static final Logger log = LoggerFactory.getLogger(WorkerController.class);

    private final AtomicReference<WorkerState> state = new AtomicReference<>(WorkerState.STARTING);
    private final List<Consumer<WorkerState>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TaskStatus>> taskListeners = new CopyOnWriteArrayList<>();

    /**
     * What the worker is doing right now, for the UI.
     *
     * @param description e.g. "Transcribiendo", or {@code null} when idle
     * @param percent     0–100, or -1 when unknown
     */
    public record TaskStatus(String description, int percent) {
        public static final TaskStatus NONE = new TaskStatus(null, -1);
    }

    private volatile String problem;
    /** Why this worker can't take tasks, or null (see {@link #setUnusable}). */
    private volatile String unusable;
    /** The user paused: kept apart from the state, which a reconnect passes through CONNECTING. */
    private volatile boolean paused;
    private volatile ServerConnection connection;
    private volatile TaskOrchestrator orchestrator;

    /** Called once during startup, after the objects are constructed. */
    public void wire(ServerConnection connection, TaskOrchestrator orchestrator) {
        this.connection = connection;
        this.orchestrator = orchestrator;
    }

    public WorkerState getState() {
        return state.get();
    }

    /**
     * Transitions to CONNECTING; called before every connection attempt. A
     * rejected worker goes through CONNECTING too, so a later failure of another
     * kind (the network) doesn't keep showing the old rejection.
     */
    public void onConnecting() {
        transition(WorkerState.CONNECTING);
    }

    /**
     * Called when the server's WELCOME arrives: IDLE, or back to PAUSED when the
     * user had paused before the connection dropped.
     */
    public void onConnected() {
        if (unusable != null) {
            problem = unusable;
            transition(WorkerState.NOT_READY);
            return;
        }
        problem = null;
        transition(paused ? WorkerState.PAUSED : WorkerState.IDLE);
    }

    /**
     * This worker can't do tasks (a tool is missing, the autotest failed):
     * once connected it shows {@code reason} instead of waiting for tasks.
     */
    public void setUnusable(String reason) {
        this.unusable = reason;
    }

    /**
     * The server refused the connection, or the configuration can't work:
     * {@code reason} says what to fix (docs/DESIGN.md §8.2).
     */
    public void onRejected(String reason) {
        WorkerState s = state.get();
        if (s == WorkerState.STOPPING || s == WorkerState.STOPPED) return;
        String previous = problem;
        problem = reason;
        if (s == WorkerState.REJECTED) {
            // Same state, new reason: tell the listeners anyway so the text updates
            if (!reason.equals(previous)) listeners.forEach(l -> l.accept(WorkerState.REJECTED));
        } else {
            transition(WorkerState.REJECTED);
        }
    }

    /** Why the worker is REJECTED or NOT_READY, or {@code null}. */
    public String getProblem() {
        return problem;
    }

    /** Transitions to WORKING when the first task starts; IDLE when the last one ends. */
    public void onTaskStarted() {
        if (state.get() == WorkerState.IDLE) transition(WorkerState.WORKING);
    }

    public void onTaskFinished(int remaining) {
        if (remaining == 0) {
            if (state.get() == WorkerState.WORKING) transition(WorkerState.IDLE);
            taskListeners.forEach(l -> l.accept(TaskStatus.NONE));
        }
    }

    /** Called on every progress report of the running task. */
    public void onTaskProgress(String description, int percent) {
        TaskStatus status = new TaskStatus(description, percent);
        taskListeners.forEach(l -> l.accept(status));
    }

    /** Subscribes to task progress updates (called on the task's thread). */
    public void addTaskListener(Consumer<TaskStatus> listener) {
        taskListeners.add(listener);
    }

    // ── User actions (docs/DESIGN.md §8.3) ──────────────────────────────────

    /**
     * Pauses acceptance of new tasks. The current task (if any) finishes normally.
     */
    public void pause() {
        WorkerState s = state.get();
        if (s == WorkerState.IDLE || s == WorkerState.WORKING) {
            paused = true;
            orchestrator.setPaused(true);
            transition(WorkerState.PAUSED);
            log.info("Worker paused — no new tasks will be accepted");
        }
    }

    /** Resumes acceptance of tasks after a pause. */
    public void resume() {
        if (state.get() == WorkerState.PAUSED) {
            paused = false;
            orchestrator.setPaused(false);
            transition(WorkerState.IDLE);
            log.info("Worker resumed");
        }
    }

    /** Pause when running, resume when paused — what the window and tray buttons do. */
    public void togglePause() {
        if (state.get() == WorkerState.PAUSED) resume();
        else pause();
    }

    /** Graceful stop: releases all active tasks and closes the WebSocket. */
    public void stop() {
        WorkerState s = state.get();
        if (s == WorkerState.STOPPING || s == WorkerState.STOPPED) return;
        transition(WorkerState.STOPPING);
        if (connection != null) connection.shutdown();
        transition(WorkerState.STOPPED);
    }

    /** Subscribes to state changes. Called on the thread that triggers the transition. */
    public void addStateListener(Consumer<WorkerState> listener) {
        listeners.add(listener);
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private void transition(WorkerState next) {
        WorkerState prev = state.getAndSet(next);
        if (prev != next) {
            log.debug("WorkerState {} → {}", prev, next);
            listeners.forEach(l -> l.accept(next));
        }
    }
}
