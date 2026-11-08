-- Control plane: the only data Baton itself keeps about a company.
-- No people, sources or claims live here; those stay in the company's own database.
CREATE TABLE tenant (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug               VARCHAR(40)  NOT NULL UNIQUE,
    name               VARCHAR(120) NOT NULL,
    -- The company's database. Credentials are AES-256-GCM sealed with the server master key.
    -- uses_home_db = true only for the built-in demo workspace, which lives in Baton's own database.
    uses_home_db       BOOLEAN      NOT NULL DEFAULT FALSE,
    db_url_sealed      TEXT,
    db_user_sealed     TEXT,
    db_password_sealed TEXT,
    -- Per-company data key (encrypts redacted originals and staged uploads), sealed with the master key.
    data_key_sealed    TEXT         NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (uses_home_db OR (db_url_sealed IS NOT NULL AND db_user_sealed IS NOT NULL AND db_password_sealed IS NOT NULL))
);
