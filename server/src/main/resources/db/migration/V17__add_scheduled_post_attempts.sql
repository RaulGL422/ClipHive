-- Failed attempts of a publication, for the growing retry delay (DESIGN.md §11.3).
ALTER TABLE scheduled_post ADD COLUMN attempts INT NOT NULL DEFAULT 0;
