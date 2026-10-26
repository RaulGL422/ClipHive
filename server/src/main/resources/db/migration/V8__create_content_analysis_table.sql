-- Claude's content analysis output (DESIGN.md §10.2) - segment ids, never
-- timestamps; the server translates ids to real times and validates them.
-- Nullable analysis columns: if Claude fails after retries, the job proceeds
-- without cuts and goes to review instead of getting stuck (R12).
CREATE TABLE content_analysis (
    id                        UUID PRIMARY KEY,
    video_job_id              UUID NOT NULL UNIQUE REFERENCES video_job(id),
    content_start_segment_id  VARCHAR(64),
    content_end_segment_id    VARCHAR(64),
    removed_segments_json     JSONB,
    proposed_clips_json       JSONB,
    content_type              VARCHAR(32),
    review_status             VARCHAR(32) NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
