-- Uploads done by hand when a platform refuses one (DESIGN.md §11.3).
ALTER TABLE scheduled_post ADD COLUMN published_manually BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE scheduled_post ADD COLUMN manual_alert_sent BOOLEAN NOT NULL DEFAULT FALSE;
