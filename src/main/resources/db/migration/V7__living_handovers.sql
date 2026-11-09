-- Questions a successor asks while reading a shared handover, routed to the person leaving.
ALTER TABLE gap_question ADD COLUMN asked_by UUID REFERENCES app_user (id);
ALTER TABLE gap_question ADD COLUMN share_id UUID REFERENCES handover_share (id) ON DELETE SET NULL;

-- The exact text that was hashed at approval, so an export can be checked byte for byte.
-- (jsonb normalises key order and spacing, so the jsonb column alone cannot reproduce the hash.)
ALTER TABLE handover_version ADD COLUMN snapshot_text TEXT;

-- When a recipient is moved to a newer version, the version they had before (for "what changed").
ALTER TABLE handover_share ADD COLUMN previous_version_no INT;
