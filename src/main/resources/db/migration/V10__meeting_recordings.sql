-- Meeting recordings: transcribed into a timestamped transcript. The audio itself stays in source_blob, encrypted
-- with the workspace key, so a claim can play the moment it cites; the lead can delete it at any time.
ALTER TABLE source DROP CONSTRAINT source_type_check;
ALTER TABLE source ADD CONSTRAINT source_type_check CHECK (type IN
    ('GITHUB', 'SLACK', 'GOOGLE_DRIVE', 'TASK_CSV', 'DOCUMENT', 'MEETING_NOTES', 'MEETING_RECORDING', 'CHAT_EXPORT',
     'TESTIMONY'));
