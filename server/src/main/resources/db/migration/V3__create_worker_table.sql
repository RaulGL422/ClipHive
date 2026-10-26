-- Registered workers (DESIGN.md §7 "Alta de workers"). Only the token hash is
-- stored - the real token is shown once at creation time and never again.
CREATE TABLE worker (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(255) NOT NULL,
    token_hash         VARCHAR(255) NOT NULL UNIQUE,
    revoked_at         TIMESTAMPTZ,
    last_capabilities  JSONB,
    last_seen_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
