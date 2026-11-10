-- Repairs handovers hit by the conflict re-resolution bug (fixed in the same release): when two claims ended up
-- superseding each other, both were shown as fact. The lead's latest decision is kept; the older, contradicting
-- link is removed. Statuses are recomputed the next time the handover is scored (reopen, review or approval).
DELETE FROM claim_link older
USING claim_link newer
WHERE older.kind = 'SUPERSEDES' AND newer.kind = 'SUPERSEDES'
  AND older.from_claim_id = newer.to_claim_id
  AND older.to_claim_id = newer.from_claim_id
  AND (older.created_at, older.id) < (newer.created_at, newer.id);
