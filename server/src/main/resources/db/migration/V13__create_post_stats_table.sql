-- Periodic metrics snapshot per publication (DESIGN.md §12.2). No fixed
-- schema in metrics_json yet - pending confirming what each platform API
-- actually exposes.
CREATE TABLE post_stats (
    id                  UUID PRIMARY KEY,
    scheduled_post_id   UUID NOT NULL REFERENCES scheduled_post(id),
    captured_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    metrics_json        JSONB NOT NULL
);

CREATE INDEX idx_post_stats_scheduled_post_id ON post_stats(scheduled_post_id);
