-- One-time links for "forgot password" and for new people setting their first password.
-- Only a SHA-256 of the token is stored, so a copy of the database cannot be used to sign in.
CREATE TABLE password_reset (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    purpose    VARCHAR(10) NOT NULL CHECK (purpose IN ('RESET', 'INVITE')),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX password_reset_user ON password_reset (user_id) WHERE used_at IS NULL;
