-- A publication waiting out a platform limit (DESIGN.md §11.3, §10.4).
ALTER TABLE scheduled_post ADD COLUMN postponed BOOLEAN NOT NULL DEFAULT FALSE;
