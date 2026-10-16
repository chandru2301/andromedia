CREATE TABLE IF NOT EXISTS users (
    trial_id TEXT PRIMARY KEY,
    clerk_user_id TEXT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_credentials (
    id UUID PRIMARY KEY,
    trial_id TEXT NOT NULL REFERENCES users (trial_id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    encrypted_payload TEXT NOT NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'active',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (trial_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_user_credentials_trial_id ON user_credentials (trial_id);
