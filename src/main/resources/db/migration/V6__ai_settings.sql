-- Bring-your-own AI: the company's own provider account. One row per workspace database.
-- The API key is encrypted with the workspace's key (AES-256-GCM); only its last 4 characters are kept readable.
CREATE TABLE ai_setting (
    id                 SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    provider           VARCHAR(20)  NOT NULL CHECK (provider IN ('GROQ', 'OPENAI', 'OPENROUTER', 'CUSTOM')),
    base_url           VARCHAR(300) NOT NULL,
    model              VARCHAR(120) NOT NULL,
    api_key_ciphertext BYTEA        NOT NULL,
    api_key_iv         BYTEA        NOT NULL,
    key_hint           VARCHAR(8)   NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_by         UUID REFERENCES app_user (id),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One row per call that reached the provider (cache replays are not counted). No prompt or reply text.
CREATE TABLE ai_call (
    id         BIGSERIAL PRIMARY KEY,
    at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    provider   VARCHAR(20)  NOT NULL,
    model      VARCHAR(120) NOT NULL,
    prompt_id  VARCHAR(60)  NOT NULL,
    tokens_in  INT          NOT NULL DEFAULT 0,
    tokens_out INT          NOT NULL DEFAULT 0,
    ok         BOOLEAN      NOT NULL
);

CREATE INDEX ai_call_at ON ai_call (at);
