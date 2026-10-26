package io.github.raulgl422.cliphive.worker.task;

import io.github.raulgl422.cliphive.protocol.message.TaskAssign;

import java.util.concurrent.Future;

/**
 * In-flight task state kept by {@link TaskOrchestrator} while the worker is
 * processing it. The {@link Future} can be cancelled on TASK_CANCEL or shutdown
 * to interrupt the underlying process.
 */
public class ActiveTask {

    private final TaskAssign assign;
    private volatile Future<?> future;
    /** Last PROGRESS percent sent for this task (-1 before the first one). */
    private volatile int lastPercent = -1;

    public ActiveTask(TaskAssign assign) {
        this.assign = assign;
    }

    public TaskAssign assign() { return assign; }

    public void setFuture(Future<?> f)        { this.future = f; }

    public int  getLastPercent()              { return lastPercent; }
    public void setLastPercent(int percent)   { this.lastPercent = percent; }

    /** Attempts to cancel the underlying task execution. */
    public void cancel() {
        Future<?> f = future;
        if (f != null) f.cancel(true);
    }
}
