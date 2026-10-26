-- Connected TikTok/Instagram/YouTube accounts (DESIGN.md §11). Tokens are
-- encrypted at the application layer with CLIPHIVE_ENCRYPTION_KEY before
-- being stored here - this column just holds text, never a plaintext secret.
CREATE TABLE social_account (
    id                        UUID PRIMARY KEY,
    platform                  VARCHAR(32) NOT NULL,
    external_account_id       VARCHAR(255) NOT NULL,
    access_token_encrypted    TEXT NOT NULL,
    refresh_token_encrypted   TEXT,
    expires_at                TIMESTAMPTZ,
    connected_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (platform, external_account_id)
);
