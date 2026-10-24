-- Stamp conversations with their area so shared (public) conversations and admin conversation/feedback
-- views are isolated by area. Nullable before a conversation is locked to a playbook or profile.
ALTER TABLE conversations
    ADD COLUMN area VARCHAR(255)
    CONSTRAINT fk_conversations_area REFERENCES areas(name)
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- 1. Backfill from the most recent agent run's orchestrator profile area
UPDATE conversations c
SET area = sub.area
FROM (
    SELECT DISTINCT ON (ar.conversation_id)
        ar.conversation_id,
        op.area
    FROM agent_runs ar
    JOIN orchestrator_profiles op ON op.name = ar.profile_name
    WHERE ar.conversation_id IS NOT NULL
    ORDER BY ar.conversation_id, ar.created_at DESC, ar.id DESC
) sub
WHERE c.id = sub.conversation_id
  AND c.area IS NULL;

-- 2. Backfill remaining conversations from the most recent message's playbook area
UPDATE conversations c
SET area = sub.area
FROM (
    SELECT DISTINCT ON (m.conversation_id)
        m.conversation_id,
        p.area
    FROM messages m
    JOIN playbooks p ON p.name = m.playbook
    WHERE m.playbook IS NOT NULL
    ORDER BY m.conversation_id, m.created_at DESC, m.id DESC
) sub
WHERE c.id = sub.conversation_id
  AND c.area IS NULL;

-- 3. Default remaining unlinked conversations to OIM when that area exists
UPDATE conversations
SET area = 'OIM'
WHERE area IS NULL
  AND EXISTS (SELECT 1 FROM areas WHERE name = 'OIM');

CREATE INDEX idx_conversations_area ON conversations(area);

-- Partial index for public conversations by area.
-- Note: 'conversations' has no 'visibility' column; public conversations are tagged with 'public'.
CREATE INDEX idx_conversations_visibility_area ON conversations(area, created_at DESC)
WHERE 'public' = ANY(tags);
