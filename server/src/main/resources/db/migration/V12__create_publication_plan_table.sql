-- One daily Claude publishing plan per date (DESIGN.md §10.4), kept so the
-- superadmin can audit what Claude proposed vs. what the server adjusted.
CREATE TABLE publication_plan (
    id                           UUID PRIMARY KEY,
    plan_date                   DATE NOT NULL UNIQUE,
    input_summary_json          JSONB NOT NULL,
    output_json                 JSONB NOT NULL,
    validations_applied_json    JSONB NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
