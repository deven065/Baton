-- Live connectors: Slack channels and Google Drive folders next to GitHub.
ALTER TABLE source DROP CONSTRAINT source_type_check;
ALTER TABLE source ADD CONSTRAINT source_type_check CHECK (type IN
    ('GITHUB', 'SLACK', 'GOOGLE_DRIVE', 'TASK_CSV', 'DOCUMENT', 'MEETING_NOTES', 'CHAT_EXPORT', 'TESTIMONY'));

-- The company's own credentials for those tools: a GitHub token, a Slack bot token, a Google service-account key.
-- Encrypted with the workspace key (AES-256-GCM); only a short hint stays readable.
CREATE TABLE integration (
    kind              VARCHAR(20) PRIMARY KEY CHECK (kind IN ('GITHUB', 'SLACK', 'GOOGLE_DRIVE')),
    secret_ciphertext BYTEA        NOT NULL,
    secret_iv         BYTEA        NOT NULL,
    hint              VARCHAR(120) NOT NULL,
    updated_by        UUID REFERENCES app_user (id),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
