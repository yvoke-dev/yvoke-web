-- Playbook-level system prompt selection.
-- Allows playbooks to define which system prompt to use (e.g. for specialized/coding/review playbooks).
-- NULL means the conversation or area/global default system prompt applies.
ALTER TABLE playbooks ADD COLUMN system_prompt VARCHAR(255)
    CONSTRAINT fk_playbooks_system_prompt REFERENCES system_prompts(name)
    ON UPDATE CASCADE ON DELETE SET NULL;

CREATE INDEX idx_playbooks_system_prompt ON playbooks(system_prompt);
