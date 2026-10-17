-- Playbook versioning: positive integer version on playbooks (existing rows start at 2),
-- and playbook_version attribution on messages and agent_steps (backfilled to 1 where a playbook was used).

ALTER TABLE playbooks
    ADD COLUMN version INTEGER NOT NULL DEFAULT 1
    CONSTRAINT ck_playbooks_version_positive CHECK (version >= 1);

UPDATE playbooks
   SET version = 2;

ALTER TABLE messages
    ADD COLUMN playbook_version INTEGER
    CONSTRAINT ck_messages_playbook_version_positive
        CHECK (playbook_version IS NULL OR (playbook IS NOT NULL AND btrim(playbook) <> '' AND playbook_version >= 1));

UPDATE messages
   SET playbook_version = 1
 WHERE playbook IS NOT NULL
   AND btrim(playbook) <> '';

ALTER TABLE agent_steps
    ADD COLUMN playbook_version INTEGER
    CONSTRAINT ck_agent_steps_playbook_version_positive
        CHECK (playbook_version IS NULL OR (playbook_name IS NOT NULL AND btrim(playbook_name) <> '' AND playbook_version >= 1));

UPDATE agent_steps
   SET playbook_version = 1
 WHERE playbook_name IS NOT NULL
   AND btrim(playbook_name) <> '';
