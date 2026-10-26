-- Editorial plan (DESIGN.md §10.2): the server's adjustments to Claude's plan,
-- and each clip's section and place in its series.
ALTER TABLE content_analysis ADD COLUMN plan_notes_json JSONB;
ALTER TABLE clip ADD COLUMN section_title VARCHAR(200);
ALTER TABLE clip ADD COLUMN part_index INTEGER;
ALTER TABLE clip ADD COLUMN part_count INTEGER;
