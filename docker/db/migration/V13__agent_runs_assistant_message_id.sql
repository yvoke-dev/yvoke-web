-- Records the delivered assistant message id on an agent run.
-- Enables linking assistant answer messages to their orchestrator runs in MAS mode.
ALTER TABLE agent_runs ADD COLUMN assistant_message_id UUID CONSTRAINT fk_agent_runs_assistant_messages REFERENCES messages(id) ON DELETE SET NULL;

CREATE INDEX idx_agent_runs_assistant_message_id ON agent_runs (assistant_message_id);
