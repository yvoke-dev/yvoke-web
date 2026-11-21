-- V18: Additive hardening — worker lease/heartbeat columns, case-insensitive collection
-- uniqueness, and upper-bound invariants on orchestrator profile limits.

ALTER TABLE ingestion_jobs ADD COLUMN worker_id TEXT;
ALTER TABLE ingestion_jobs ADD COLUMN heartbeat_at TIMESTAMPTZ;

CREATE UNIQUE INDEX ux_collections_name_lower ON collections (LOWER(name));

-- Structural DB ceilings (50 / 200) sit above the runtime config defaults (10 / 50) in application.yml.
ALTER TABLE orchestrator_profiles
  ADD CONSTRAINT ck_orchestrator_profiles_max_review_rounds
  CHECK (max_review_rounds >= 1 AND max_review_rounds <= 50);

ALTER TABLE orchestrator_profiles
  ADD CONSTRAINT ck_orchestrator_profiles_max_specialist_calls
  CHECK (max_specialist_calls >= 1 AND max_specialist_calls <= 200);
