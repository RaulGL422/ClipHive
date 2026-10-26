-- Uploaded as a draft (TikTok) or private (YouTube): waits until you confirm it's published (DESIGN.md §11.3).
ALTER TABLE scheduled_post ADD COLUMN awaiting_confirmation BOOLEAN NOT NULL DEFAULT FALSE;
