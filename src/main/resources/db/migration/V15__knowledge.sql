-- Always-on knowledge layer: what the project has decided and why, captured from its sources as they are read,
-- independent of any handover.
CREATE TABLE decision (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    source_id     UUID         NOT NULL REFERENCES source (id) ON DELETE CASCADE,
    document_id   UUID         REFERENCES document (id) ON DELETE SET NULL,
    chunk_id      UUID         REFERENCES chunk (id) ON DELETE SET NULL,
    -- sha256 of source + document + the decision's words: the same decision stays the same row after a re-read,
    -- so a lead's confirmation is not lost.
    fingerprint   VARCHAR(64)  NOT NULL,
    doc_ref       VARCHAR(600),
    doc_title     VARCHAR(300) NOT NULL,
    source_type   VARCHAR(20)  NOT NULL,
    url           VARCHAR(1000),
    title         VARCHAR(400) NOT NULL,
    reasoning     TEXT,
    alternatives  TEXT[]       NOT NULL DEFAULT '{}',
    decided_by    VARCHAR(120),
    decided_at    TIMESTAMPTZ,
    quote         TEXT         NOT NULL,
    topic         VARCHAR(120),
    confidence    NUMERIC(3, 2) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    review        VARCHAR(12)  NOT NULL DEFAULT 'AUTO' CHECK (review IN ('AUTO', 'CONFIRMED', 'REJECTED')),
    reviewed_by   UUID REFERENCES app_user (id) ON DELETE SET NULL,
    reviewed_at   TIMESTAMPTZ,
    -- copied from the passage it was cut from; changed together with the source's access
    sensitivity   SMALLINT     NOT NULL CHECK (sensitivity BETWEEN 0 AND 3),
    acl_groups    TEXT[]       NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (project_id, fingerprint)
);
CREATE INDEX decision_project_time ON decision (project_id, decided_at DESC NULLS LAST);
CREATE INDEX decision_source ON decision (source_id);

-- When a source was last mined for decisions; null means it has not been, and is mined the next time anyone looks.
ALTER TABLE source ADD COLUMN mined_at TIMESTAMPTZ;
