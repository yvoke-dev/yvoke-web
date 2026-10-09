-- Performance indexes for foreign key cascades, joins, and collection-level scans.
CREATE INDEX idx_llm_call_logs_message_id ON llm_call_logs (message_id)
    WHERE message_id IS NOT NULL;
CREATE INDEX idx_chunks_collection_id ON chunks (collection_id);
CREATE INDEX idx_ingestion_jobs_collection_id ON ingestion_jobs (collection_id);
