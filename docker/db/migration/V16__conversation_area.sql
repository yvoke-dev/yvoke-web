-- Stamp conversations with their area so shared (public) conversations and admin conversation/feedback
-- views are isolated by area. Nullable before a conversation is locked to a playbook or profile.
ALTER TABLE conversations
    ADD COLUMN area VARCHAR(255)
    CONSTRAINT fk_conversations_area REFERENCES areas(name)
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- Everything that exists today is OIM's.
UPDATE conversations
SET area = 'OIM'
WHERE area IS NULL;

CREATE INDEX idx_conversations_area ON conversations(area);

-- Partial index for public conversations by area.
-- Note: 'conversations' has no 'visibility' column; public conversations are tagged with 'public'.
CREATE INDEX idx_conversations_visibility_area ON conversations(area, created_at DESC)
WHERE 'public' = ANY(tags);
