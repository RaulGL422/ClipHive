-- Structured logs, filterable by level/component/job/worker in the
-- superadmin (DESIGN.md §12.2), with limited retention coherent with the
-- "no trace left" principle (R15). The retention cleanup job itself is
-- later-phase work; the created_at index here is what makes it cheap.
CREATE TABLE log_event (
    id            UUID PRIMARY KEY,
    level         VARCHAR(16) NOT NULL,
    component     VARCHAR(128) NOT NULL,
    message       TEXT NOT NULL,
    context_json  JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_log_event_created_at ON log_event(created_at);
CREATE INDEX idx_log_event_level_component ON log_event(level, component);
