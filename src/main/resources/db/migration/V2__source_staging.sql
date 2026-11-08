-- Uploaded files waiting for the ingest job. Stored AES-GCM encrypted and deleted as soon as
-- the job has redacted and chunked them, so raw text never sits in the database in the clear.
CREATE TABLE source_blob (
    source_id  UUID PRIMARY KEY REFERENCES source (id) ON DELETE CASCADE,
    filename   VARCHAR(300) NOT NULL,
    mime       VARCHAR(100),
    ciphertext BYTEA NOT NULL,
    iv         BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE source
    ADD COLUMN document_count INT NOT NULL DEFAULT 0,
    ADD COLUMN chunk_count    INT NOT NULL DEFAULT 0,
    ADD COLUMN redaction_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_error     TEXT;
