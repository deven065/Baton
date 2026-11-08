-- M3: reconstruction pipeline support.

-- Handover lifecycle gains SHARED (LLD L3).
ALTER TABLE handover DROP CONSTRAINT handover_status_check;
ALTER TABLE handover ADD CONSTRAINT handover_status_check
    CHECK (status IN ('DRAFT', 'RECONSTRUCTING', 'IN_REVIEW', 'APPROVED', 'SHARED', 'ARCHIVED'));

-- Names the departing person appears under in sources (display name, first name, GitHub login...).
ALTER TABLE handover ADD COLUMN departing_aliases TEXT[] NOT NULL DEFAULT '{}';

-- Resume support: stages already completed in this run, warnings shown to the reviewer.
ALTER TABLE extraction_run
    ADD COLUMN job_id      UUID,
    ADD COLUMN stages_done TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN warnings    JSONB  NOT NULL DEFAULT '[]'::jsonb;

-- Evidence drafts are written by extraction and checked by the verifier.
-- valid = the quote was located in the chunk text by code. Only valid evidence is ever shown as proof.
ALTER TABLE evidence
    ADD COLUMN valid       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN verified_at TIMESTAMPTZ,
    ADD COLUMN model_quote TEXT;

-- Topic key used to find contradictions ("public launch date"), and safety flags.
ALTER TABLE claim
    ADD COLUMN subject VARCHAR(200),
    ADD COLUMN flags   TEXT[] NOT NULL DEFAULT '{}';

-- Cached LLM responses: keyed by sha256(prompt id + model + system + user).
-- Re-runs only pay for new chunks, and the offline demo replays recorded responses.
CREATE TABLE llm_cache (
    key        CHAR(64) PRIMARY KEY,
    prompt_id  VARCHAR(40) NOT NULL,
    model      VARCHAR(80) NOT NULL,
    response   TEXT NOT NULL,
    tokens_in  INT,
    tokens_out INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A conflict question quotes two claims; it is visible only to someone who may see both.
ALTER TABLE gap_question ADD COLUMN related_claim_id UUID REFERENCES claim (id) ON DELETE CASCADE;

CREATE TABLE timeline_event (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handover_id UUID NOT NULL REFERENCES handover (id) ON DELETE CASCADE,
    claim_id    UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    at          TIMESTAMPTZ NOT NULL,
    kind        VARCHAR(20) NOT NULL CHECK (kind IN ('DECISION', 'CHANGE', 'OPEN_DISCUSSION', 'RISK')),
    title       VARCHAR(300) NOT NULL
);

CREATE INDEX timeline_handover_idx ON timeline_event (handover_id, at);
CREATE INDEX claim_subject_idx ON claim (handover_id, subject);
