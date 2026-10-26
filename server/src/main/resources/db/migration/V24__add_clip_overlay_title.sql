-- Short title burned on top of the clip (DESIGN.md §10.3, §9.2).
ALTER TABLE clip ADD COLUMN overlay_title VARCHAR(120);
