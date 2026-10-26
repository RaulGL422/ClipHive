package io.github.raulgl422.cliphive.protocol.state;

/**
 * Lifecycle of one unit of worker work ({@link TaskType#TRANSCRIBE} chunk or
 * {@link TaskType#RENDER_CLIP}).
 *
 * <p>Normal path: {@code PENDING -> ASSIGNED -> RUNNING -> DONE}. A task can
 * bounce back from {@code ASSIGNED} or {@code RUNNING} to {@code PENDING} for
 * reassignment - a crashed/disconnected worker, a stalled task (no
 * {@code PROGRESS} within the configured timeout), or a voluntary
 * {@code TASK_RELEASE}. Every reassignment increments the {@code attempt}
 * counter on the task row, which protocol messages carry as a fencing token
 * so a worker that reconnects late can't act on a task it no longer owns -
 * see docs/DESIGN.md section 6.3.
 *
 * <p>{@link #FAILED} is reached only after exhausting the retry budget -
 * docs/DESIGN.md section 6.3 tracks <b>two separate counters</b> (failures
 * vs. disconnections) so a flaky network doesn't burn through the same
 * budget as a genuinely broken task. {@code TASK_RELEASE} doesn't count
 * against either counter.
 *
 * <p>See docs/DESIGN.md section 5.3.
 */
public enum TaskState {
    /** Waiting in the queue, not yet handed to a worker. */
    PENDING,
    /** Handed to a worker, waiting for {@code TASK_ACCEPT}/{@code TASK_REJECT}. */
    ASSIGNED,
    /** Worker accepted the task and is actively working on it. */
    RUNNING,
    /** Terminal: worker reported {@code TASK_DONE}. */
    DONE,
    /** Terminal: retry budget exhausted - see the two-counter rule above. */
    FAILED,
    /** Terminal: cancelled from the superadmin ({@code TASK_CANCEL}). */
    CANCELLED
}
