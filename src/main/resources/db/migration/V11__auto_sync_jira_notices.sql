-- Jira as a live source, automatic sync of live sources, and notices for the lead when new evidence arrives.
ALTER TABLE source DROP CONSTRAINT source_type_check;
ALTER TABLE source ADD CONSTRAINT source_type_check CHECK (type IN
    ('GITHUB', 'SLACK', 'GOOGLE_DRIVE', 'JIRA', 'TASK_CSV', 'DOCUMENT', 'MEETING_NOTES', 'MEETING_RECORDING',
     'CHAT_EXPORT', 'TESTIMONY'));

ALTER TABLE integration DROP CONSTRAINT integration_kind_check;
ALTER TABLE integration ADD CONSTRAINT integration_kind_check CHECK (kind IN ('GITHUB', 'SLACK', 'GOOGLE_DRIVE', 'JIRA'));

-- Automatic sync. content_hash is a fingerprint of everything the last read returned (hashes of each document's raw
-- text, which never leaves memory otherwise), so a scheduled check can tell "nothing changed" without re-indexing.
ALTER TABLE source ADD COLUMN auto_sync       BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE source ADD COLUMN content_hash    TEXT;
ALTER TABLE source ADD COLUMN last_checked_at TIMESTAMPTZ;
ALTER TABLE source ADD COLUMN last_changed_at TIMESTAMPTZ;
ALTER TABLE source ADD COLUMN sync_error      TEXT;
ALTER TABLE document ADD COLUMN content_hash  TEXT;

-- "New evidence": at most one open notice per handover and kind; a later change updates it in place.
CREATE TABLE handover_notice (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handover_id  UUID        NOT NULL REFERENCES handover (id) ON DELETE CASCADE,
    kind         VARCHAR(30) NOT NULL CHECK (kind IN ('SOURCE_CHANGED', 'SYNC_FAILED')),
    message      TEXT        NOT NULL,
    details      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    dismissed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX handover_notice_open ON handover_notice (handover_id, kind) WHERE dismissed_at IS NULL;
