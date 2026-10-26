-- One row per (clip, platform) (DESIGN.md §5.4). scheduled_at is stored in
-- UTC, shown as Europe/Madrid in the superadmin (§10.4). external_id is the
-- idempotency key after a server restart mid-upload (§11.3).
CREATE TABLE scheduled_post (
    id             UUID PRIMARY KEY,
    clip_id        UUID NOT NULL REFERENCES clip(id),
    platform       VARCHAR(32) NOT NULL,
    scheduled_at   TIMESTAMPTZ NOT NULL,
    state          VARCHAR(32) NOT NULL,
    origin         VARCHAR(32) NOT NULL,
    external_id    VARCHAR(255),
    error_message  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (clip_id, platform)
);

CREATE INDEX idx_scheduled_post_state ON scheduled_post(state);
CREATE INDEX idx_scheduled_post_scheduled_at ON scheduled_post(scheduled_at);
