-- The playlist that holds a video's clips, by platform (DESIGN.md §11.2).
ALTER TABLE video_job ADD COLUMN playlist_ids_json JSONB;
