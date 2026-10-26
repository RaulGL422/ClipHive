-- Dubbed audio option (DESIGN.md §4 steps 1–3): the language of the dubbed
-- track to use (null = original audio) and whether the source offered it.
ALTER TABLE video_job ADD COLUMN audio_language VARCHAR(16);
ALTER TABLE video_job ADD COLUMN dubbed_audio_available BOOLEAN;
