-- Manual clip mode (DESIGN.md §4.1): the clip ranges are marked by hand in the
-- superadmin instead of being proposed by Claude.
ALTER TABLE video_job ADD COLUMN clip_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO';
ALTER TABLE video_job ADD COLUMN manual_ranges_json JSONB;
ALTER TABLE video_job ADD COLUMN selection_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
