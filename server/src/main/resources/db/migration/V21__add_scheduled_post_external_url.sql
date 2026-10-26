-- Link to a publication when the platform can't derive it from the id
-- (Instagram's permalink) — DESIGN.md §14.2.
ALTER TABLE scheduled_post ADD COLUMN external_url VARCHAR(512);
