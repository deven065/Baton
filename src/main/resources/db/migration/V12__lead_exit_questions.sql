-- Questions the lead writes themselves for the exit interview (next to the ones Baton generates).
ALTER TABLE gap_question ADD COLUMN added_by UUID REFERENCES app_user (id) ON DELETE SET NULL;
