package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import io.github.raulgl422.cliphive.server.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    /**
     * Atomic task assignment (docs/DESIGN.md section 6.3): locks the highest
     * priority {@code PENDING} tasks with {@code SELECT ... FOR UPDATE SKIP
     * LOCKED} so two assignment attempts running at the same time can never
     * grab the same task - a transaction that hits a locked row skips it
     * instead of blocking.
     *
     * <p><b>Must be called inside a transaction</b> (the caller needs
     * {@code @Transactional}): the row lock this takes is only held, and
     * only means anything, for the duration of that transaction. The
     * {@code idx_task_pending_queue} index (V10 migration) on
     * {@code (state, priority, created_at)} is what keeps this off a
     * sequential scan.
     *
     * <p>{@code 'PENDING'} is a literal matching
     * {@link TaskState#PENDING} by name -
     * a native query can't reference the Java enum directly.
     */
    @Query(value = """
            SELECT * FROM task
            WHERE state = 'PENDING'
              -- RENDER_CLIP is created when clips are planned, before its video
              -- piece is cut and (review mode) before approval; it may only go
              -- out once its job is RENDERING (DESIGN.md §6.4). Dispatched
              -- earlier, the worker got a 404 for the not-yet-cut source.
              AND (type <> 'RENDER_CLIP' OR EXISTS (
                    SELECT 1 FROM clip c
                    JOIN video_job j ON j.id = c.video_job_id
                    WHERE c.id = task.clip_id AND j.state = 'RENDERING'))
            ORDER BY priority ASC, created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Task> lockNextPending(@Param("limit") int limit);

    /** Every task of a job: its TRANSCRIBE chunks and the RENDER_CLIP of each of its clips. */
    @Query("""
            SELECT t FROM Task t LEFT JOIN t.clip c
            WHERE t.videoJob.id = :jobId OR c.videoJob.id = :jobId
            ORDER BY t.createdAt
            """)
    List<Task> findAllForJob(@Param("jobId") UUID jobId);

    List<Task> findByAssignedWorkerIdAndStateIn(UUID workerId,
            Collection<TaskState> states);

    /** Active tasks with no update (accept, progress) since {@code cutoff} — the §6.5 progress timeout. */
    List<Task> findByStateInAndUpdatedAtBefore(Collection<TaskState> states, Instant cutoff);

    int countByAssignedWorkerIdAndStateIn(UUID workerId,
            Collection<TaskState> states);

    int countByVideoJobIdAndState(UUID videoJobId,
            TaskState state);

    int countByVideoJobId(UUID videoJobId);

    /** All tasks for a given clip (used to look up the RENDER_CLIP task by clip). */
    List<Task> findByClipId(UUID clipId);

    List<Task> findByVideoJobIdAndTypeAndState(UUID videoJobId, TaskType type, TaskState state);
}
