-- Records the delivered assistant message id on an agent run.
-- Enables linking assistant answer messages to their orchestrator runs in MAS mode.
ALTER TABLE agent_runs ADD COLUMN assistant_message_id UUID CONSTRAINT fk_agent_runs_assistant_messages REFERENCES messages(id) ON DELETE SET NULL;

CREATE INDEX idx_agent_runs_assistant_message_id ON agent_runs (assistant_message_id);

-- Backfill existing runs where message_id is already the assistant message (desktop client runs)
UPDATE agent_runs r
SET assistant_message_id = r.message_id
FROM messages m
WHERE r.assistant_message_id IS NULL
  AND r.message_id = m.id
  AND m.role = 'assistant';

-- Backfill existing web runs where message_id is the user message
UPDATE agent_runs r
SET assistant_message_id = sub.id
FROM (
    SELECT DISTINCT ON (r2.id) r2.id AS run_id, m_asst.id
    FROM agent_runs r2
    JOIN messages m_user ON m_user.id = r2.message_id AND m_user.role = 'user'
    JOIN messages m_asst ON m_asst.conversation_id = r2.conversation_id
                         AND m_asst.role = 'assistant'
                         AND m_asst.created_at >= m_user.created_at
    WHERE r2.assistant_message_id IS NULL
    ORDER BY r2.id, m_asst.created_at ASC
) sub
WHERE r.id = sub.run_id;
