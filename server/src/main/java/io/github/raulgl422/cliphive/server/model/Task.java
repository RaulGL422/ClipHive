package io.github.raulgl422.cliphive.server.model;

import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One unit of worker work - docs/DESIGN.md sections 5.3, 6 and 7. Exactly
 * one of {@link #videoJob} (a {@code TRANSCRIBE} chunk) / {@link #clip}
 * ({@code RENDER_CLIP}) is set, matching {@link #type} - enforced in the
 * database by {@code chk_task_exactly_one_target} (V10 migration) and here
 * by only exposing the two factory methods below instead of a public
 * constructor.
 *
 * <p>{@link #attempt} is the fencing token from section 6.3: it increases on
 * every reassignment and is echoed by the worker on every message about this
 * task, so a worker presumed dead that reconnects late can't act on a task
 * it no longer owns. {@link #failureCount} and {@link #disconnectCount} are
 * the two separate counters (max 3 / max 10) from the same section - a flaky
 * connection must not burn the same budget as a genuinely broken task;
 * {@link #release()} (voluntary {@code TASK_RELEASE}) bumps neither.
 *
 * <p>State, attempt and counters only change through the methods below, so
 * the attempt (the fencing token, §6.3) can't be rewritten from outside.
 */
@Entity
@Table(name = "task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskState state;

    @Setter
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "disconnect_count", nullable = false)
    private int disconnectCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_worker_id")
    private Worker assignedWorker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_job_id")
    private VideoJob videoJob;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clip_id")
    private Clip clip;

    @Setter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters_json", nullable = false)
    private Map<String, Object> parametersJson;

    @Column(name = "last_progress_percent")
    private Integer lastProgressPercent;

    @Column(name = "last_progress_phase")
    private String lastProgressPhase;

    @Column(name = "last_progress_at")
    private Instant lastProgressAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Task(TaskType type, VideoJob videoJob, Clip clip, Map<String, Object> parametersJson) {
        this.type = type;
        this.videoJob = videoJob;
        this.clip = clip;
        this.parametersJson = parametersJson;
        this.state = TaskState.PENDING;
        this.priority = videoJob != null ? videoJob.getPriority() : clip.getClipIndex();
        this.attempt = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** A TRANSCRIBE task for one audio chunk of {@code videoJob}. */
    public static Task forTranscribe(VideoJob videoJob, Map<String, Object> parametersJson) {
        return new Task(TaskType.TRANSCRIBE, videoJob, null, parametersJson);
    }

    /** A RENDER_CLIP task for {@code clip}. */
    public static Task forRenderClip(Clip clip, Map<String, Object> parametersJson) {
        return new Task(TaskType.RENDER_CLIP, null, clip, parametersJson);
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public void setState(TaskState state) {
        this.state = state;
        if (state == TaskState.DONE) {
            // The worker's last PROGRESS is 95 % ("uploading"); finishing is 100 %
            this.lastProgressPercent = 100;
            this.lastProgressPhase = "done";
        }
        touch();
    }

    /** Hands this task to a worker: ASSIGNED, same attempt. */
    public void assignTo(Worker worker) {
        this.assignedWorker = worker;
        this.state = TaskState.ASSIGNED;
        touch();
    }

    /**
     * Returns this task to the queue for reassignment, bumping the fencing
     * token - a crashed/disconnected worker or a stalled task (section 6.5).
     */
    public void reassign() {
        this.assignedWorker = null;
        this.state = TaskState.PENDING;
        this.attempt++;
        touch();
    }

    /** Voluntary hand-back ({@code TASK_RELEASE}) - bumps attempt, spends no counter. */
    public void release() {
        reassign();
    }

    /** Records a failure (counts toward the max-3 budget) and reassigns if not exhausted. */
    public void recordFailure(int maxFailures) {
        this.failureCount++;
        if (this.failureCount >= maxFailures) {
            this.state = TaskState.FAILED;
            touch();
        } else {
            reassign();
        }
    }

    /** Records a dropped connection (counts toward the separate max-10 budget). */
    public void recordDisconnect(int maxDisconnects) {
        this.disconnectCount++;
        if (this.disconnectCount >= maxDisconnects) {
            this.state = TaskState.FAILED;
            touch();
        } else {
            reassign();
        }
    }

    /**
     * Superadmin retry of a FAILED task: back to the queue with both counters
     * reset and a new fencing token (DESIGN.md §5.3).
     */
    public void retry() {
        this.failureCount = 0;
        this.disconnectCount = 0;
        this.lastProgressPercent = null;
        this.lastProgressPhase = null;
        this.lastProgressAt = null;
        reassign();
    }

    /**
     * Superadmin cancellation. Bumps the fencing token so whatever the worker
     * that held it sends afterwards (TASK_FAILED, TASK_DONE...) is ignored as
     * stale and can't put the task back in the queue.
     */
    public void cancel() {
        this.assignedWorker = null;
        this.state = TaskState.CANCELLED;
        this.attempt++;
        touch();
    }

    public void recordProgress(int percent, String phase) {
        this.lastProgressPercent = percent;
        this.lastProgressPhase = phase;
        this.lastProgressAt = Instant.now();
        touch();
    }
}
