-- Add prototype flag to orchestrator profiles (default false)
ALTER TABLE orchestrator_profiles ADD COLUMN prototype BOOLEAN NOT NULL DEFAULT FALSE;
