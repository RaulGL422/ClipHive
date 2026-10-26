-- A submitted link and everything learned about it (DESIGN.md §4, §5.1).
-- source_platform (YouTube/Twitch/Kick...) is the origin site, NOT a
-- publishing target - unrelated to the `platform` column in social_account /
-- scheduled_post (TikTok/Instagram/YouTube), despite the overlap on YouTube.
CREATE TABLE video_job (
    id                UUID PRIMARY KEY,
    source_url        TEXT NOT NULL,
    source_platform   VARCHAR(32),
    state             VARCHAR(32) NOT NULL,
    language          VARCHAR(16),
    fps_mode          VARCHAR(16),
    review_mode       BOOLEAN NOT NULL,
    priority          INTEGER NOT NULL DEFAULT 0,
    title             TEXT,
    category          VARCHAR(255),
    duration_seconds  INTEGER,
    source_fps        INTEGER,
    resolution         VARCHAR(32),
    rejection_reason  TEXT,
    file_hash         VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_video_job_state ON video_job(state);
