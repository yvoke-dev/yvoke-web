-- Records agent steps that FAILED, not only the ones that completed.
--
-- agent_steps rows were written after the agentic call returned, so a step that threw left no row
-- at all: an orchestrated run that died on an HTTP 429 persisted four successful steps and nothing
-- about the two calls that actually failed. The failing role, playbook, model, prompt and
-- partially-streamed output were frame-locals and were lost with the stack, which left the admin
-- trace showing a run that simply stopped for no visible reason.
--
-- 'ok' is the default so every existing row keeps its meaning without a backfill.
ALTER TABLE agent_steps ADD COLUMN status TEXT NOT NULL DEFAULT 'ok';
ALTER TABLE agent_steps ADD COLUMN error TEXT;

-- The admin timeline reads a run's steps in order and highlights the failed ones.
CREATE INDEX idx_agent_steps_status ON agent_steps (status) WHERE status <> 'ok';
