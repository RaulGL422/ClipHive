-- Alerts already sent (DESIGN.md §12.3: low-queue alert, extensible to
-- failed publications / expiring tokens), so the "send once on threshold
-- cross" rule doesn't repeat and the superadmin can list them.
CREATE TABLE notification (
    id             UUID PRIMARY KEY,
    type           VARCHAR(64) NOT NULL,
    recipient      VARCHAR(255) NOT NULL,
    sent_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    status         VARCHAR(32) NOT NULL,
    metadata_json  JSONB
);

CREATE INDEX idx_notification_type_sent_at ON notification(type, sent_at);
