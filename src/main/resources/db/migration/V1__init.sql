-- Baton V1 schema. Matches the HLD component table:
-- identity/project, source, reconstruct + evidence, review, handover, job/audit.
--
-- Sensitivity is stored as SMALLINT so "sensitivity <= :clearance" works directly:
--   0 PUBLIC, 1 INTERNAL, 2 CONFIDENTIAL, 3 RESTRICTED

CREATE EXTENSION IF NOT EXISTS vector;

-- ───────────────────────── identity / project ─────────────────────────

CREATE TABLE app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    display_name  VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    org_role      VARCHAR(20)  NOT NULL DEFAULT 'MEMBER' CHECK (org_role IN ('ADMIN', 'MEMBER')),
    clearance     SMALLINT     NOT NULL DEFAULT 1 CHECK (clearance BETWEEN 0 AND 3),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_group (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(60) NOT NULL UNIQUE,
    description VARCHAR(255)
);

CREATE TABLE user_group_member (
    user_id  UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES user_group (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, group_id)
);

CREATE TABLE project (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    created_by  UUID REFERENCES app_user (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project_member (
    project_id UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role       VARCHAR(20) NOT NULL CHECK (role IN ('PROJECT_LEAD', 'MEMBER')),
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

-- ───────────────────────────── source ─────────────────────────────

CREATE TABLE source (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id              UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    type                    VARCHAR(20) NOT NULL CHECK (type IN ('GITHUB', 'TASK_CSV', 'DOCUMENT', 'MEETING_NOTES', 'CHAT_EXPORT', 'TESTIMONY')),
    title                   VARCHAR(200) NOT NULL,
    config                  JSONB NOT NULL DEFAULT '{}'::jsonb,
    sensitivity             SMALLINT NOT NULL DEFAULT 1 CHECK (sensitivity BETWEEN 0 AND 3),
    acl_groups              TEXT[] NOT NULL DEFAULT '{}',
    authorisation_attested  BOOLEAN NOT NULL CHECK (authorisation_attested),
    attested_by             UUID NOT NULL REFERENCES app_user (id),
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SYNCING', 'READY', 'FAILED')),
    last_synced_at          TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id    UUID NOT NULL REFERENCES source (id) ON DELETE CASCADE,
    external_ref VARCHAR(500),
    title        VARCHAR(300) NOT NULL,
    author       VARCHAR(120),
    authored_at  TIMESTAMPTZ,
    url          VARCHAR(1000),
    mime         VARCHAR(100),
    sensitivity  SMALLINT NOT NULL CHECK (sensitivity BETWEEN 0 AND 3),
    acl_groups   TEXT[] NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Chunk text is ALREADY REDACTED. Raw text never reaches this table.
CREATE TABLE chunk (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id  UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    seq          INT  NOT NULL,
    text         TEXT NOT NULL,
    tsv          TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', text)) STORED,
    embedding    VECTOR(384),
    sensitivity  SMALLINT NOT NULL CHECK (sensitivity BETWEEN 0 AND 3),
    acl_groups   TEXT[] NOT NULL DEFAULT '{}',
    start_offset INT,
    end_offset   INT,
    UNIQUE (document_id, seq)
);

-- Originals of redacted secrets/PII, AES-GCM encrypted.
CREATE TABLE redaction_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    token       VARCHAR(40) NOT NULL,
    kind        VARCHAR(20) NOT NULL CHECK (kind IN ('EMAIL', 'PHONE', 'API_KEY', 'PASSWORD', 'SECRET', 'OTHER')),
    ciphertext  BYTEA NOT NULL,
    iv          BYTEA NOT NULL,
    UNIQUE (document_id, token)
);

-- ───────────────────────────── handover ─────────────────────────────

CREATE TABLE handover (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id         UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    departing_user_id  UUID NOT NULL REFERENCES app_user (id),
    title              VARCHAR(200) NOT NULL,
    scenario           VARCHAR(20) NOT NULL DEFAULT 'PLANNED' CHECK (scenario IN ('PLANNED', 'SUDDEN', 'SPLIT')),
    status             VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                       CHECK (status IN ('DRAFT', 'RECONSTRUCTING', 'IN_REVIEW', 'APPROVED', 'ARCHIVED')),
    current_version_no INT,
    created_by         UUID NOT NULL REFERENCES app_user (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            INT NOT NULL DEFAULT 0
);

-- ─────────────────────── reconstruct + evidence ───────────────────────

CREATE TABLE extraction_run (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handover_id UUID NOT NULL REFERENCES handover (id) ON DELETE CASCADE,
    status      VARCHAR(20) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    stage       VARCHAR(30),
    stats       JSONB NOT NULL DEFAULT '{}'::jsonb,
    prompt_version VARCHAR(40),
    model       VARCHAR(80),
    error       TEXT,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE TABLE claim (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handover_id       UUID NOT NULL REFERENCES handover (id) ON DELETE CASCADE,
    extraction_run_id UUID REFERENCES extraction_run (id) ON DELETE SET NULL,
    type              VARCHAR(30) NOT NULL CHECK (type IN (
                          'DECISION', 'OPEN_WORK', 'OWNER', 'RISK', 'DEPENDENCY', 'BLOCKER',
                          'RECURRING_ISSUE', 'CONTACT', 'OPEN_QUESTION', 'PROCESS', 'ACCESS')),
    title             VARCHAR(300) NOT NULL,
    body              TEXT,
    attributes        JSONB NOT NULL DEFAULT '{}'::jsonb,
    status            VARCHAR(20) NOT NULL CHECK (status IN (
                          'VERIFIED', 'SINGLE_SOURCE', 'INFERRED', 'CONFLICTING', 'MISSING_EVIDENCE', 'STALE')),
    confidence        NUMERIC(4, 3) NOT NULL DEFAULT 0 CHECK (confidence BETWEEN 0 AND 1),
    reasons           JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- MAX of its evidence sensitivity; recomputed in code, never lowered by an LLM
    sensitivity       SMALLINT NOT NULL CHECK (sensitivity BETWEEN 0 AND 3),
    acl_groups        TEXT[] NOT NULL DEFAULT '{}',
    critical          BOOLEAN NOT NULL DEFAULT FALSE,
    review_state      VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                      CHECK (review_state IN ('PENDING', 'ACCEPTED', 'EDITED', 'REJECTED', 'NEEDS_INFO')),
    origin            VARCHAR(20) NOT NULL CHECK (origin IN ('EXTRACTED', 'REPO_SIGNAL', 'TESTIMONY', 'HUMAN')),
    cluster_hash      VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           INT NOT NULL DEFAULT 0
);

CREATE TABLE evidence (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id     UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    chunk_id     UUID NOT NULL REFERENCES chunk (id) ON DELETE CASCADE,
    quote        TEXT NOT NULL,
    match_score  NUMERIC(4, 3) NOT NULL CHECK (match_score BETWEEN 0 AND 1),
    start_offset INT,
    end_offset   INT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE claim_link (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    to_claim_id   UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    kind          VARCHAR(20) NOT NULL CHECK (kind IN ('CONFLICTS_WITH', 'SUPERSEDES', 'DUPLICATES', 'SUPPORTS')),
    resolved      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (from_claim_id, to_claim_id, kind),
    CHECK (from_claim_id <> to_claim_id)
);

-- ───────────────────────────── review ─────────────────────────────

CREATE TABLE claim_revision (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id   UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    version    INT  NOT NULL,
    actor_id   UUID NOT NULL REFERENCES app_user (id),
    before     JSONB,
    after      JSONB NOT NULL,
    reason     VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE gap_question (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handover_id    UUID NOT NULL REFERENCES handover (id) ON DELETE CASCADE,
    claim_id       UUID REFERENCES claim (id) ON DELETE SET NULL,
    kind           VARCHAR(30) NOT NULL CHECK (kind IN ('MISSING_OWNER', 'MISSING_EVIDENCE', 'CONFLICT', 'MISSING_CHECKLIST', 'OTHER')),
    question       VARCHAR(1000) NOT NULL,
    priority       SMALLINT NOT NULL DEFAULT 2 CHECK (priority BETWEEN 1 AND 3),
    status         VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ANSWERED', 'SKIPPED', 'UNANSWERED')),
    answer_text    VARCHAR(2000),
    answer_sensitivity SMALLINT CHECK (answer_sensitivity BETWEEN 0 AND 3),
    answered_by    UUID REFERENCES app_user (id),
    answered_at    TIMESTAMPTZ,
    testimony_chunk_id UUID REFERENCES chunk (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─────────────────────── handover versions & shares ───────────────────────

-- Immutable once written (see trigger below).
CREATE TABLE handover_version (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handover_id UUID NOT NULL REFERENCES handover (id) ON DELETE CASCADE,
    version_no  INT  NOT NULL,
    snapshot    JSONB NOT NULL,
    sha256      CHAR(64) NOT NULL,
    approved_by UUID NOT NULL REFERENCES app_user (id),
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (handover_id, version_no)
);

CREATE TABLE checklist_item (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handover_version_id UUID NOT NULL REFERENCES handover_version (id) ON DELETE CASCADE,
    phase               VARCHAR(10) NOT NULL CHECK (phase IN ('DAY1', 'WEEK1', 'MONTH1')),
    position            INT NOT NULL,
    text                VARCHAR(500) NOT NULL,
    claim_id            UUID REFERENCES claim (id) ON DELETE SET NULL,
    sensitivity         SMALLINT NOT NULL CHECK (sensitivity BETWEEN 0 AND 3),
    acl_groups          TEXT[] NOT NULL DEFAULT '{}'
);

CREATE TABLE handover_share (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handover_version_id UUID NOT NULL REFERENCES handover_version (id) ON DELETE CASCADE,
    recipient_user_id   UUID NOT NULL REFERENCES app_user (id),
    role_label          VARCHAR(60) NOT NULL,
    created_by          UUID NOT NULL REFERENCES app_user (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    acknowledged_at     TIMESTAMPTZ,
    CHECK (expires_at <= created_at + INTERVAL '90 days')
);

CREATE TABLE checklist_progress (
    share_id          UUID NOT NULL REFERENCES handover_share (id) ON DELETE CASCADE,
    checklist_item_id UUID NOT NULL REFERENCES checklist_item (id) ON DELETE CASCADE,
    done_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (share_id, checklist_item_id)
);

-- ───────────────────────────── job / audit ─────────────────────────────

CREATE TABLE job (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind         VARCHAR(30) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    project_id   UUID REFERENCES project (id) ON DELETE CASCADE,
    created_by   UUID REFERENCES app_user (id),
    payload      JSONB NOT NULL DEFAULT '{}'::jsonb,
    progress     JSONB NOT NULL DEFAULT '{}'::jsonb,
    attempts     INT NOT NULL DEFAULT 0,
    last_error   TEXT,
    heartbeat_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only (see trigger below).
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id    UUID REFERENCES app_user (id),
    action      VARCHAR(60) NOT NULL,
    entity_type VARCHAR(40),
    entity_id   UUID,
    project_id  UUID,
    details     JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- ───────────────────────────── immutability ─────────────────────────────

CREATE FUNCTION baton_forbid_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION baton_forbid_change();

CREATE TRIGGER handover_version_immutable
    BEFORE UPDATE ON handover_version
    FOR EACH ROW EXECUTE FUNCTION baton_forbid_change();

-- ───────────────────────────── indexes (LLD L10) ─────────────────────────────

CREATE INDEX chunk_doc_idx        ON chunk (document_id, seq);
CREATE INDEX chunk_tsv_idx        ON chunk USING GIN (tsv);
CREATE INDEX chunk_emb_idx        ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunk_acl_idx        ON chunk USING GIN (acl_groups);
CREATE INDEX document_source_idx  ON document (source_id, authored_at DESC);
CREATE INDEX claim_handover_idx   ON claim (handover_id, type, status);
CREATE INDEX evidence_claim_idx   ON evidence (claim_id);
CREATE INDEX evidence_chunk_idx   ON evidence (chunk_id);
CREATE INDEX gap_handover_idx     ON gap_question (handover_id, status);
CREATE INDEX job_queue_idx        ON job (status, created_at) WHERE status = 'QUEUED';
CREATE UNIQUE INDEX one_running_run ON extraction_run (handover_id) WHERE status = 'RUNNING';
CREATE INDEX audit_project_idx    ON audit_log (project_id, at DESC);
CREATE INDEX source_project_idx   ON source (project_id);
CREATE INDEX handover_project_idx ON handover (project_id);
CREATE INDEX share_recipient_idx  ON handover_share (recipient_user_id);
