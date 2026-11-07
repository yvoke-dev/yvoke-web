-- V17: Retire CHAT system prompts; playbooks are now self-contained system prompts.
-- Ingestion prompts (KG, SUMMARIZE) remain in system_prompts.

-- 1. Drop incoming FK columns (automatically drops fk_areas_default_system_prompt,
--    idx_areas_default_system_prompt, fk_playbooks_system_prompt, idx_playbooks_system_prompt)
ALTER TABLE areas DROP COLUMN default_system_prompt;
ALTER TABLE playbooks DROP COLUMN system_prompt;

-- 2. Remove retired active-chat-prompt setting
DELETE FROM app_config WHERE key = 'default-chat-prompt';

-- 3. Canonicalize legacy ingestion type aliases before constraint enforcement
UPDATE system_prompts
   SET type = 'KG'
 WHERE upper(btrim(type)) IN ('KNOWLEDGE_GRAPH', 'KNOWLEDGE-GRAPH');

UPDATE system_prompts
   SET type = 'SUMMARIZE'
 WHERE upper(btrim(type)) IN ('SUMMARY', 'DB', 'DATABASE');

-- 4. Purge retired CHAT / AGENTIC / RAG / non-ingestion rows
DELETE FROM system_prompts
 WHERE upper(btrim(type)) NOT IN ('KG', 'SUMMARIZE');

-- 5. Canonicalize remaining ingestion prompt types before adding constraint
UPDATE system_prompts
   SET type = upper(btrim(type))
 WHERE upper(btrim(type)) IN ('KG', 'SUMMARIZE');

-- 6. Enforce valid ingestion prompt types (case-insensitive to preserve external/legacy
--    lowercase writes pinned by SystemPromptRepositoryIT)
ALTER TABLE system_prompts
  ADD CONSTRAINT ck_system_prompts_type
  CHECK (upper(btrim(type)) IN ('KG', 'SUMMARIZE'));
