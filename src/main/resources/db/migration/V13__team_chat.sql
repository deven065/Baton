-- Team chat: one running conversation per project, visible to the project's members only.
CREATE TABLE chat_message (
    id         UUID PRIMARY KEY,
    project_id UUID        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    sender_id  UUID        REFERENCES app_user (id) ON DELETE SET NULL,
    body       TEXT        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX chat_message_project_time ON chat_message (project_id, created_at DESC);
