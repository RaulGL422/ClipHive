-- One row per WebSocket connection (DESIGN.md §6.5) - lets the superadmin show
-- a worker's connection history, not just its current state.
CREATE TABLE worker_session (
    id                  UUID PRIMARY KEY,
    worker_id           UUID NOT NULL REFERENCES worker(id),
    connected_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    disconnected_at     TIMESTAMPTZ,
    disconnect_reason   VARCHAR(255),
    last_heartbeat_at   TIMESTAMPTZ
);

CREATE INDEX idx_worker_session_worker_id ON worker_session(worker_id);
