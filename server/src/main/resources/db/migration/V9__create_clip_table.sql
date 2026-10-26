-- Planned clips (DESIGN.md §5.2, §9). clip_index fixes the priority order
-- between clips of the same video job - lower index, higher priority (§6.2).
CREATE TABLE clip (
    id                UUID PRIMARY KEY,
    video_job_id      UUID NOT NULL REFERENCES video_job(id),
    clip_index        INTEGER NOT NULL,
    state             VARCHAR(32) NOT NULL,
    kept_ranges_json  JSONB NOT NULL,
    fps               INTEGER,
    title             TEXT,
    description       TEXT,
    file_reference    VARCHAR(255),
    duration_seconds  INTEGER,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (video_job_id, clip_index)
);

CREATE INDEX idx_clip_video_job_id ON clip(video_job_id);
CREATE INDEX idx_clip_state ON clip(state);
