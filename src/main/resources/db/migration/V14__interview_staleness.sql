-- AI interview mode: follow-up questions the interviewer asks after an answer, and questions about stale docs.
ALTER TABLE gap_question DROP CONSTRAINT gap_question_kind_check;
ALTER TABLE gap_question ADD CONSTRAINT gap_question_kind_check CHECK (kind IN
    ('MISSING_OWNER', 'MISSING_EVIDENCE', 'CONFLICT', 'MISSING_CHECKLIST', 'OTHER', 'FOLLOW_UP', 'STALE_DOC'));
ALTER TABLE gap_question ADD COLUMN parent_id        UUID REFERENCES gap_question (id) ON DELETE CASCADE;
ALTER TABLE gap_question ADD COLUMN follow_up_reason VARCHAR(300);
-- 'ai' when a model wrote the follow-up, 'rules' when the built-in interviewer did (no AI key or the AI failed).
ALTER TABLE gap_question ADD COLUMN follow_up_by     VARCHAR(10);
-- One follow-up per answered question, even if two requests race.
CREATE UNIQUE INDEX gap_question_one_follow_up ON gap_question (parent_id) WHERE parent_id IS NOT NULL;

-- Staleness detection: a document that still describes something a later code change renamed, removed or upgraded.
-- doc_ref survives a re-read of the source (documents are replaced on every read), so a lead's decision sticks.
CREATE TABLE stale_finding (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID         NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    doc_ref         VARCHAR(600) NOT NULL,
    document_id     UUID REFERENCES document (id) ON DELETE SET NULL,
    chunk_id        UUID REFERENCES chunk (id) ON DELETE SET NULL,
    doc_title       VARCHAR(300) NOT NULL,
    doc_quote       VARCHAR(600) NOT NULL,
    term            VARCHAR(200) NOT NULL,
    replacement     VARCHAR(200),
    kind            VARCHAR(20)  NOT NULL CHECK (kind IN ('RENAMED', 'REPLACED', 'REMOVED', 'VERSION')),
    change_ref      VARCHAR(80)  NOT NULL,
    change_text     VARCHAR(600) NOT NULL,
    change_at       TIMESTAMPTZ  NOT NULL,
    change_chunk_id UUID REFERENCES chunk (id) ON DELETE SET NULL,
    change_url      VARCHAR(1000),
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CONFIRMED', 'DISMISSED')),
    decided_by      UUID REFERENCES app_user (id) ON DELETE SET NULL,
    decided_at      TIMESTAMPTZ,
    detected_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (project_id, doc_ref, term, change_ref)
);
CREATE INDEX stale_finding_project ON stale_finding (project_id, status);

ALTER TABLE gap_question ADD COLUMN stale_finding_id UUID REFERENCES stale_finding (id) ON DELETE SET NULL;
