-- Visible name of a connected account (e.g. the YouTube channel), shown in the
-- superadmin's accounts page (DESIGN.md §14.2).
ALTER TABLE social_account ADD COLUMN display_name VARCHAR(255);
