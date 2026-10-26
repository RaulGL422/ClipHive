-- Non-secret, hot-editable superadmin settings (DESIGN.md §12.2) - defaults for
-- language/fps/review mode, publishing mode, clip duration limits, low-queue
-- alert threshold, etc. Untyped per row: each reader parses what it expects.
CREATE TABLE setting (
    key        VARCHAR(255) PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
