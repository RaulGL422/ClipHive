-- One row per video job: the whisper.cpp word-level transcript, already
-- stitched together by the server from the worker's per-chunk results,
-- discarding the overlap between chunks (DESIGN.md §4 step 5).
CREATE TABLE transcript (
    id            UUID PRIMARY KEY,
    video_job_id  UUID NOT NULL UNIQUE REFERENCES video_job(id),
    language      VARCHAR(16),
    words_json    JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
