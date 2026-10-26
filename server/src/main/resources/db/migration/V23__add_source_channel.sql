-- Channel or author of the original video, credited in clip descriptions (DESIGN.md §10.3).
ALTER TABLE video_job ADD COLUMN source_channel VARCHAR(255);
