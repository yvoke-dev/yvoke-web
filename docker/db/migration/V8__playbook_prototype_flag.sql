-- Add prototype flag to playbooks (default false)
ALTER TABLE playbooks ADD COLUMN prototype BOOLEAN NOT NULL DEFAULT FALSE;
