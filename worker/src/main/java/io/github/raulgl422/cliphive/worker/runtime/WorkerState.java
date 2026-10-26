package io.github.raulgl422.cliphive.worker.runtime;

/**
 * Observable states of the worker process (docs/DESIGN.md §8.2, §8.3).
 */
public enum WorkerState {
    /** Startup sequence in progress (loading config, hardware detection, model download). */
    STARTING,
    /** Connecting or reconnecting to the server. */
    CONNECTING,
    /**
     * The server refused the worker (unknown or revoked token, incompatible
     * protocol) or the server URL is invalid: retrying alone won't fix it
     * (docs/DESIGN.md §8.2). The reason is {@code WorkerController.getProblem()}.
     */
    REJECTED,
    /**
     * Connected, but a required tool is missing or the autotest failed: the
     * worker advertises no capacity, so it gets no tasks (docs/DESIGN.md §8.5).
     * The reason is {@code WorkerController.getProblem()}.
     */
    NOT_READY,
    /** Connected and waiting for tasks. */
    IDLE,
    /** Processing one or more tasks. */
    WORKING,
    /** New tasks are declined; current task finishes normally. */
    PAUSED,
    /** Shutdown requested — releasing tasks and closing the connection. */
    STOPPING,
    /** Worker has fully stopped. */
    STOPPED
}
