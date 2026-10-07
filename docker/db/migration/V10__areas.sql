-- Areas (Yvoke for Claude, task P1-12). An area is a knowledge base such as OIM: a named group of
-- system prompts, collections, playbooks and orchestrator profiles. Every one of those belongs to
-- exactly one area; nothing exists outside an area.
--
-- Additive only: the previous release keeps running against this schema during a rolling deploy.
-- It inserts rows without naming an area, so each new `area` column defaults to 'OIM'; new code
-- always passes the area explicitly. A later release can drop the defaults.

CREATE TABLE areas (
    name VARCHAR(255) PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT,
    prototype BOOLEAN NOT NULL DEFAULT FALSE,
    -- Defaults a new client session starts with. Deleting the item clears the default; renaming it
    -- carries over.
    default_system_prompt VARCHAR(255)
        CONSTRAINT fk_areas_default_system_prompt REFERENCES system_prompts(name)
        ON UPDATE CASCADE ON DELETE SET NULL,
    default_playbook VARCHAR(255)
        CONSTRAINT fk_areas_default_playbook REFERENCES playbooks(name)
        ON UPDATE CASCADE ON DELETE SET NULL,
    default_profile VARCHAR(255)
        CONSTRAINT fk_areas_default_profile REFERENCES orchestrator_profiles(name)
        ON UPDATE CASCADE ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Everything that exists today is OIM's.
INSERT INTO areas (name, title) VALUES ('OIM', 'OIM');

-- Renaming an area carries over to its members; an area that still has members cannot be deleted.
ALTER TABLE system_prompts ADD COLUMN area VARCHAR(255) NOT NULL DEFAULT 'OIM'
    CONSTRAINT fk_system_prompts_area REFERENCES areas(name) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE collections ADD COLUMN area VARCHAR(255) NOT NULL DEFAULT 'OIM'
    CONSTRAINT fk_collections_area REFERENCES areas(name) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE playbooks ADD COLUMN area VARCHAR(255) NOT NULL DEFAULT 'OIM'
    CONSTRAINT fk_playbooks_area REFERENCES areas(name) ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE orchestrator_profiles ADD COLUMN area VARCHAR(255) NOT NULL DEFAULT 'OIM'
    CONSTRAINT fk_orchestrator_profiles_area REFERENCES areas(name) ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE INDEX idx_system_prompts_area ON system_prompts(area);
CREATE INDEX idx_collections_area ON collections(area);
CREATE INDEX idx_playbooks_area ON playbooks(area);
CREATE INDEX idx_orchestrator_profiles_area ON orchestrator_profiles(area);
-- The defaults are foreign keys too: renaming or deleting a prompt, playbook or profile looks them up.
CREATE INDEX idx_areas_default_system_prompt ON areas(default_system_prompt);
CREATE INDEX idx_areas_default_playbook ON areas(default_playbook);
CREATE INDEX idx_areas_default_profile ON areas(default_profile);

-- OIM's defaults, where the rows exist: the playbook every OIM session started with and the profile
-- named after the area. The default system prompt stays empty, so OIM keeps following the admin's
-- Active Default Chat System Prompt rather than a copy of today's choice.
UPDATE areas SET
    default_playbook = (SELECT name FROM playbooks WHERE name = 'oim-full'),
    default_profile = (SELECT name FROM orchestrator_profiles WHERE name = 'OIM')
WHERE name = 'OIM';
