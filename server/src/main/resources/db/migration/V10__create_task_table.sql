-- The work queue (DESIGN.md §5.3, §6, §7). Exactly one of video_job_id
-- (a TRANSCRIBE chunk) / clip_id (a RENDER_CLIP) is set, matching `type`.
--
-- attempt is the fencing token (§6.3): bumped on every reassignment, echoed
-- by the worker on every message about this task so a worker presumed dead
-- that reconnects late can't act on a task it no longer owns.
--
-- failure_count and disconnect_count are the TWO SEPARATE counters from
-- §6.3 (max 3 / max 10) - a flaky connection must not burn the same budget
-- as a genuinely broken task. TASK_RELEASE bumps neither.
--
-- last_progress_at feeds the stalled-task timeout (§6.5): a task can keep
-- answering heartbeats while the work itself is stuck.
CREATE TABLE task (
    id                     UUID PRIMARY KEY,
    type                   VARCHAR(32) NOT NULL,
    state                  VARCHAR(32) NOT NULL,
    priority               INTEGER NOT NULL DEFAULT 0,
    attempt                INTEGER NOT NULL DEFAULT 1,
    failure_count          INTEGER NOT NULL DEFAULT 0,
    disconnect_count       INTEGER NOT NULL DEFAULT 0,
    assigned_worker_id     UUID REFERENCES worker(id),
    video_job_id           UUID REFERENCES video_job(id),
    clip_id                UUID REFERENCES clip(id),
    parameters_json        JSONB NOT NULL,
    last_progress_percent  INTEGER,
    last_progress_phase    VARCHAR(64),
    last_progress_at       TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_exactly_one_target CHECK (num_nonnulls(video_job_id, clip_id) = 1)
);

-- Supports "grab the next PENDING task ordered by priority" under
-- SELECT ... FOR UPDATE SKIP LOCKED (§6.3) without a sequential scan.
CREATE INDEX idx_task_pending_queue ON task(state, priority, created_at);
CREATE INDEX idx_task_assigned_worker_id ON task(assigned_worker_id);
