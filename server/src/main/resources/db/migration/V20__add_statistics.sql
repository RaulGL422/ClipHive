-- Statistics (DESIGN.md §12.4).

-- When a publication went out: drives how often its stats are collected and
-- the "views by publication hour" chart.
ALTER TABLE scheduled_post ADD COLUMN published_at TIMESTAMPTZ;
UPDATE scheduled_post SET published_at = updated_at WHERE state = 'PUBLISHED';

-- Periodic snapshot of a connected account (subscribers, total views, videos).
CREATE TABLE account_stats (
    id                  UUID PRIMARY KEY,
    social_account_id   UUID NOT NULL REFERENCES social_account(id) ON DELETE CASCADE,
    captured_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    metrics_json        JSONB NOT NULL
);

CREATE INDEX idx_account_stats_account_captured ON account_stats(social_account_id, captured_at);
CREATE INDEX idx_post_stats_post_captured ON post_stats(scheduled_post_id, captured_at);
