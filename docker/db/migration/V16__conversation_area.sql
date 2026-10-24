-- Stamp conversations with their area so shared (public) conversations and admin conversation/feedback
-- views are isolated by area. Nullable before a conversation is locked to a playbook or profile.
ALTER TABLE conversations
    ADD COLUMN area VARCHAR(255)
    CONSTRAINT fk_conversations_area REFERENCES areas(name)
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- Backfill from orchestrator_profiles via agent_runs first (agent_runs timestamp is started_at)
UPDATE conversations c
SET area = op.area
FROM (
    SELECT DISTINCT ON (conversation_id) conversation_id, profile_name
    FROM agent_runs
    WHERE conversation_id IS NOT NULL
    ORDER BY conversation_id, started_at ASC
) ar
JOIN orchestrator_profiles op ON op.name = ar.profile_name
WHERE c.id = ar.conversation_id
  AND c.area IS NULL;

-- Backfill remaining conversations from the first message's playbook
UPDATE conversations c
SET area = p.area
FROM (
    SELECT DISTINCT ON (conversation_id) conversation_id, playbook
    FROM messages
    WHERE playbook IS NOT NULL AND btrim(playbook) <> ''
    ORDER BY conversation_id, created_at ASC
) m
JOIN playbooks p ON p.name = m.playbook
WHERE c.id = m.conversation_id
  AND c.area IS NULL;

CREATE INDEX idx_conversations_area ON conversations(area);

-- Partial index for public conversations by area.
-- Note: 'conversations' has no 'visibility' column; public conversations are tagged with 'public'.
CREATE INDEX idx_conversations_visibility_area ON conversations(area, created_at DESC)
WHERE 'public' = ANY(tags);
