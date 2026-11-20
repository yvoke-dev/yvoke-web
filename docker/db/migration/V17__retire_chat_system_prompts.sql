-- V17: Canonicalize legacy ingestion prompt type aliases (non-destructive expand phase).
--
-- Playbooks are now self-contained system prompts, and CHAT system prompts are retired in app
-- code. Per the expand-contract migration rule, dropping areas.default_system_prompt, dropping
-- playbooks.system_prompt, deleting CHAT rows, and adding ck_system_prompts_type are deferred to
-- the subsequent release so 1.3.0 can survive rollout and rollback without a database restore.

UPDATE system_prompts
   SET type = 'KG'
 WHERE upper(btrim(type)) IN ('KNOWLEDGE_GRAPH', 'KNOWLEDGE-GRAPH');

UPDATE system_prompts
   SET type = 'SUMMARIZE'
 WHERE upper(btrim(type)) IN ('SUMMARY', 'DB', 'DATABASE');

UPDATE system_prompts
   SET type = upper(btrim(type))
 WHERE upper(btrim(type)) IN ('KG', 'SUMMARIZE');
