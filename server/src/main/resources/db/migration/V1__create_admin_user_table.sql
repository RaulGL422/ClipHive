-- Single superadmin account, created on first boot from env vars (DESIGN.md §12.1).
-- 2FA is enabled later from the web UI, hence totp_secret_encrypted is nullable.
CREATE TABLE admin_user (
    id                     UUID PRIMARY KEY,
    username               VARCHAR(255) NOT NULL UNIQUE,
    password_hash          VARCHAR(255) NOT NULL,
    totp_secret_encrypted  TEXT,
    totp_enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
