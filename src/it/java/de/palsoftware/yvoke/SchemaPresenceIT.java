package de.palsoftware.yvoke;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class SchemaPresenceIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final List<String> EXPECTED_TABLES = List.of(
        "documents",
        "chunks",
        "entities",
        "relationships",
        "ingestion_jobs",
        "users",
        "conversations",
        "messages",
        "message_feedback",
        "retrieval_logs",
        "summary_cache",
        "section_summaries",
        "audit_log",
        "agent_runs",
        "agent_steps",
        "message_tool_calls"
    );

    @Test
    public void testAllTablesExist() {
        for (String table : EXPECTED_TABLES) {
            assertThat(tableExists(table))
                .withFailMessage("Table '%s' should exist in the schema", table)
                .isTrue();
        }
    }

    @Test
    public void testCriticalIndexesExist() {
        // HNSW index on chunks.embedding
        assertThat(indexExists("chunks", "chunks_embedding_hnsw_idx"))
            .withFailMessage("HNSW index chunks_embedding_hnsw_idx should exist")
            .isTrue();

        // BM25 index on chunks(id, text)
        assertThat(indexExists("chunks", "chunks_bm25_idx"))
            .withFailMessage("BM25 index chunks_bm25_idx should exist")
            .isTrue();

        // Trigram index on entities.name
        assertThat(indexExists("entities", "entities_name_trgm_idx"))
            .withFailMessage("Trigram index entities_name_trgm_idx should exist")
            .isTrue();

        // BTree index on chunks(document_id, sort_order)
        assertThat(indexExists("chunks", "chunks_document_id_sort_order_idx"))
            .withFailMessage("BTree index chunks_document_id_sort_order_idx should exist")
            .isTrue();

        // Partial index supporting the queued-job claim query (M10)
        assertThat(indexExists("ingestion_jobs", "ingestion_jobs_queued_idx"))
            .withFailMessage("Partial index ingestion_jobs_queued_idx should exist")
            .isTrue();
    }

    @Test
    public void testPerformanceIndexesExist() {
        // Wave 2.1 (PRF-04/06/07/08/09): indexes backing the hot filter/sort/join paths.
        assertThat(indexExists("llm_call_logs", "idx_llm_call_logs_user_id_created_at"))
            .withFailMessage("Index idx_llm_call_logs_user_id_created_at should exist (PRF-04)")
            .isTrue();
        assertThat(indexExists("audit_log", "idx_audit_log_created_at"))
            .withFailMessage("Index idx_audit_log_created_at should exist (PRF-06)")
            .isTrue();
        assertThat(indexExists("retrieval_logs", "idx_retrieval_logs_collection_id"))
            .withFailMessage("Index idx_retrieval_logs_collection_id should exist (PRF-07)")
            .isTrue();
        assertThat(indexExists("agent_runs", "idx_agent_runs_started_at"))
            .withFailMessage("Index idx_agent_runs_started_at should exist (PRF-08)")
            .isTrue();
        assertThat(indexExists("messages", "idx_messages_retrieved_chunk_ids_gin"))
            .withFailMessage("GIN index idx_messages_retrieved_chunk_ids_gin should exist (PRF-09)")
            .isTrue();
    }

    @Test
    public void testMigrationV11PerformanceIndexesExist() {
        // V11 (PERF-01, PERF-02, PERF-03): foreign key and collection lookup indexes.
        assertThat(indexExists("llm_call_logs", "idx_llm_call_logs_message_id"))
            .withFailMessage("Partial index idx_llm_call_logs_message_id should exist (PERF-01)")
            .isTrue();
        assertThat(indexExists("chunks", "idx_chunks_collection_id"))
            .withFailMessage("BTree index idx_chunks_collection_id should exist (PERF-02)")
            .isTrue();
        assertThat(indexExists("ingestion_jobs", "idx_ingestion_jobs_collection_id"))
            .withFailMessage("Index idx_ingestion_jobs_collection_id should exist (PERF-03)")
            .isTrue();

        // Ensure idx_llm_call_logs_message_id is partial on non-null message_id
        String llmCallLogIndexDef = jdbcTemplate.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            String.class, "idx_llm_call_logs_message_id");
        assertThat(llmCallLogIndexDef).contains("WHERE").contains("message_id IS NOT NULL");
    }

    @Test
    public void testMigrationV11PartitionIndexPropagationAndCascadeBehavior() {
        UUID collectionId = UUID.randomUUID();
        String partition = "chunks_p_" + collectionId.toString().replace("-", "");
        jdbcTemplate.update(
            "INSERT INTO collections (id, name) VALUES (?, ?)", collectionId, "v11-partition-test");
        try {
            assertThat(tableExists(partition)).isTrue();

            // Verify index on partition: check pg_indexes for this partition table
            List<String> partitionIndexDefs = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
                String.class, partition);
            assertThat(partitionIndexDefs)
                .as("Partition %s must inherit an index on (collection_id) from idx_chunks_collection_id", partition)
                .anyMatch(def -> def.contains("(collection_id)"));

            // Verify the partition index is attached to parent index idx_chunks_collection_id in pg_inherits / pg_index
            Integer attachedParentIndexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_index child_idx
                JOIN pg_class child_cls ON child_cls.oid = child_idx.indexrelid
                JOIN pg_inherits inh ON inh.inhrelid = child_cls.oid
                JOIN pg_class parent_cls ON parent_cls.oid = inh.inhparent
                WHERE parent_cls.relname = 'idx_chunks_collection_id'
                  AND child_idx.indrelid = ?::regclass
                """,
                Integer.class,
                partition);
            assertThat(attachedParentIndexCount)
                .as("Partition %s must have an index partition attached to parent idx_chunks_collection_id", partition)
                .isEqualTo(1);

            // Insert a document and a chunk
            UUID docId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO documents (id, collection_id, kind, title) VALUES (?, ?, ?, ?)",
                docId, collectionId, "manual", "Doc 1");
            UUID chunkId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO chunks (id, collection_id, document_id, text) VALUES (?, ?, ?, ?)",
                chunkId, collectionId, docId, "Some text");

            // Insert an ingestion job
            UUID jobId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO ingestion_jobs (id, collection_id, kind, source_ref, status) VALUES (?, ?, ?, ?, ?)",
                jobId, collectionId, "manual", "ref", "queued");

            Integer jobCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ingestion_jobs WHERE collection_id = ?", Integer.class, collectionId);
            assertThat(jobCount).isEqualTo(1);
        } finally {
            // Delete collection - test cascade and trigger without deadlock
            jdbcTemplate.update("DELETE FROM collections WHERE id = ?", collectionId);
        }

        // Verify partition dropped and jobs cascaded
        assertThat(tableExists(partition)).isFalse();
        Integer remainingJobs = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ingestion_jobs WHERE collection_id = ?", Integer.class, collectionId);
        assertThat(remainingJobs).isEqualTo(0);
    }

    @Test
    public void testMigrationV11MessageDeletionAndLlmCallLogsIndexIntegrity() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, entra_oid, email, display_name) VALUES (?, ?, ?, ?)",
            userId, "oid-" + userId, "v11-" + userId + "@example.com", "User 11");
        UUID convId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO conversations (id, user_id, title) VALUES (?, ?, ?)",
            convId, userId, "v11-conv");
        UUID messageId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
            messageId, convId, "user", "Hello");

        UUID logId1 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO llm_call_logs (id, message_id, source, model) VALUES (?, ?, ?, ?)",
            logId1, messageId, "chat", "gpt-4");
        UUID logId2 = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO llm_call_logs (id, message_id, source, model) VALUES (?, ?, ?, ?)",
            logId2, null, "background", "gpt-4");

        try {
            // Delete message and verify ON DELETE SET NULL works cleanly without error or deadlock
            jdbcTemplate.update("DELETE FROM messages WHERE id = ?", messageId);

            UUID referencedMessageId = jdbcTemplate.queryForObject(
                "SELECT message_id FROM llm_call_logs WHERE id = ?", UUID.class, logId1);
            assertThat(referencedMessageId).isNull();
        } finally {
            jdbcTemplate.update("DELETE FROM llm_call_logs WHERE id IN (?, ?)", logId1, logId2);
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", convId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    public void testMigrationV12MessageToolCallsTableAndCascadeDeletion() {
        assertThat(tableExists("message_tool_calls")).isTrue();
        assertThat(indexExists("message_tool_calls", "idx_message_tool_calls_created_at")).isFalse();

        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, entra_oid, email, display_name) VALUES (?, ?, ?, ?)",
            userId, "oid-" + userId, "v12-" + userId + "@example.com", "User 12");
        UUID convId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO conversations (id, user_id, title) VALUES (?, ?, ?)",
            convId, userId, "v12-conv");
        UUID messageId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
            messageId, convId, "assistant", "Response with tool calls");

        UUID toolCallId = UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO message_tool_calls (id, message_id, seq, tool_call_id, tool_name, arguments, result, is_error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            toolCallId, messageId, 0, "call_123", "search_corpus", "{\"q\":\"test\"}", "found items", false);

        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_tool_calls WHERE message_id = ?", Integer.class, messageId);
            assertThat(count).isEqualTo(1);

            // Delete message and verify ON DELETE CASCADE deletes message_tool_calls
            jdbcTemplate.update("DELETE FROM messages WHERE id = ?", messageId);

            Integer remainingToolCalls = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_tool_calls WHERE id = ?", Integer.class, toolCallId);
            assertThat(remainingToolCalls).isEqualTo(0);
        } finally {
            jdbcTemplate.update("DELETE FROM message_tool_calls WHERE id = ?", toolCallId);
            jdbcTemplate.update("DELETE FROM messages WHERE id = ?", messageId);
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", convId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    public void testMigrationV13AgentRunsAssistantMessageIdColumnAndIndex() {
        assertThat(columnExists("agent_runs", "assistant_message_id")).isTrue();
        assertThat(indexExists("agent_runs", "idx_agent_runs_assistant_message_id")).isTrue();
    }

    @Test
    public void testUniquenessIndexesExist() {
        // Wave 3b (V3): job admission control and document identity. Both are the ARBITER of an
        // ON CONFLICT in application code (JobRepository.enqueue, DocumentRepository's upsert), so
        // losing either one turns a duplicate from a silent adopt into a runtime failure.
        assertThat(indexExists("ingestion_jobs", "ux_ingestion_jobs_active_work"))
            .withFailMessage("Unique index ux_ingestion_jobs_active_work should exist (V3)")
            .isTrue();
        assertThat(indexExists("documents", "ux_documents_collection_kind_source_file_tags"))
            .withFailMessage(
                "Unique index ux_documents_collection_kind_source_file_tags should exist (V3)")
            .isTrue();

        // The job index must stay PARTIAL on the active statuses: without the predicate, a
        // completed job would block the same work from ever being enqueued again.
        String jobIndexDef = jdbcTemplate.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            String.class, "ux_ingestion_jobs_active_work");
        assertThat(jobIndexDef).contains("WHERE").contains("queued").contains("running");
    }

    @Test
    public void testChunksIsPartitionedByCollection() {
        // chunks is a LIST-partitioned parent (V11) so BM25 statistics are collection-local
        String relkind = jdbcTemplate.queryForObject(
            "SELECT relkind FROM pg_class WHERE relname = 'chunks' AND relnamespace = 'public'::regnamespace",
            String.class);
        assertThat(relkind)
            .withFailMessage("chunks should be a partitioned table (relkind 'p') but was '%s'", relkind)
            .isEqualTo("p");

        for (String trigger : List.of("trg_collections_create_chunks_partition",
                "trg_collections_drop_chunks_partition")) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger WHERE tgname = ?", Integer.class, trigger);
            assertThat(count)
                .withFailMessage("Trigger %s should exist on collections", trigger)
                .isEqualTo(1);
        }
    }

    @Test
    public void testCollectionInsertAndDeleteManagePartition() {
        UUID id = UUID.randomUUID();
        String partition = "chunks_p_" + id.toString().replace("-", "");
        jdbcTemplate.update(
            "INSERT INTO collections (id, name) VALUES (?, ?)", id, "partition-roundtrip-test");
        try {
            assertThat(tableExists(partition))
                .withFailMessage("Inserting a collection should create partition %s", partition)
                .isTrue();
        } finally {
            jdbcTemplate.update("DELETE FROM collections WHERE id = ?", id);
        }
        assertThat(tableExists(partition))
            .withFailMessage("Deleting a collection should drop partition %s", partition)
            .isFalse();
    }

    @Test
    public void testCollectionInsertManagesPartitionWithEmptySearchPath() {
        // Regression: pg_dump emits set_config('search_path', '', false) ahead of every COPY, so a
        // data-only restore inserts collections rows with no schema on the path. The partition
        // trigger must still resolve "chunks" and create the partition in public, otherwise the
        // restore aborts with "no schema has been selected to create in".
        UUID id = UUID.randomUUID();
        String partition = "chunks_p_" + id.toString().replace("-", "");

        Boolean partitionCreated = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement session = connection.createStatement()) {
                // SET LOCAL: reverts with the rollback below, so the pooled connection is handed
                // back with its normal search_path.
                session.execute("SET LOCAL search_path = ''");

                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO public.collections (id, name) VALUES (?, ?)")) {
                    insert.setObject(1, id);
                    insert.setString(2, "empty-search-path-partition-test");
                    insert.executeUpdate();
                }

                try (PreparedStatement lookup = connection.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = ?")) {
                    lookup.setString(1, partition);
                    try (ResultSet rs = lookup.executeQuery()) {
                        rs.next();
                        return rs.getInt(1) > 0;
                    }
                }
            } finally {
                // CREATE TABLE is transactional in Postgres, so this undoes the row and the
                // partition together.
                connection.rollback();
                connection.setAutoCommit(previousAutoCommit);
            }
        });

        assertThat(partitionCreated)
            .withFailMessage("Inserting a collection under an empty search_path should still create partition %s",
                partition)
            .isTrue();
    }

    @Test
    public void testPartitionTriggerFunctionsPinSearchPath() {
        for (String function : List.of("create_chunks_partition", "drop_chunks_partition")) {
            List<String> config = jdbcTemplate.queryForList(
                "SELECT unnest(proconfig) FROM pg_proc WHERE proname = ?", String.class, function);
            assertThat(config)
                .withFailMessage("Function %s should pin its search_path (proconfig was %s)", function, config)
                .anyMatch(entry -> entry.startsWith("search_path="));
        }
    }

    @Test
    public void testJobQueueColumnsExist() {
        List<String> jobColumns = List.of(
            "step", "attempts", "doc_count", "chunk_count", "entity_count", "edge_count"
        );
        for (String column : jobColumns) {
            assertThat(columnExists("ingestion_jobs", column))
                .withFailMessage("Column ingestion_jobs.%s should exist", column)
                .isTrue();
        }
    }

    /**
     * The eight corpus indexes that serve the hot filter/lookup paths and that no test names.
     *
     * <p>
     * Every one of them is invisible when it disappears: the query still returns exactly the right
     * rows, just sequentially, so no test fails, no error is logged and the only symptom is that the
     * app gets slower as the corpus grows. That is the whole reason to assert them by name — a
     * migration that renames or drops one is indistinguishable from a correct one at the row level.
     *
     * <p>
     * They are not interchangeable filler. {@code idx_documents_metadata_source_file} serves the
     * per-page Confluence version-skip ({@code DocumentRepository.getMetadataAndStatus} and
     * {@code findIdByFile} both probe {@code metadata->>'source_file'}) once per crawled page over a
     * 22k-row table — losing it turns a sync into tens of thousands of sequential scans.
     * {@code idx_collections_tags} / {@code idx_documents_tags} back the {@code &&} overlap that the
     * whole two-versions-in-one-collection design filters on, {@code idx_documents_id_nodash} and
     * {@code idx_chunks_id_nodash} back the dash-stripped prefix lookup that resolves every
     * citation, {@code idx_documents_title_trgm} backs the fuzzy title search's
     * {@code similarity()} ranking, and {@code idx_chunks_document_id_kg_ok} backs the per-row
     * kg-status subqueries the corpus browser runs for every document on the page.
     *
     * <p>
     * The db image bakes the migrations in, so this doubles as the guard the project's own runbook
     * asks for: a {@code docker compose up -d} against a stale image reports a clean Flyway run
     * while the schema it validated is not the one on disk.
     */
    @Test
    public void testCorpusFilterAndLookupIndexesExist() {
        assertThat(indexExists("collections", "idx_collections_tags"))
            .withFailMessage("GIN index idx_collections_tags should exist (tag overlap filter)")
            .isTrue();

        assertThat(indexExists("documents", "idx_documents_tags"))
            .withFailMessage("GIN index idx_documents_tags should exist (tag overlap filter)")
            .isTrue();
        assertThat(indexExists("documents", "idx_documents_collection_id"))
            .withFailMessage("Index idx_documents_collection_id should exist (collection join)")
            .isTrue();
        assertThat(indexExists("documents", "idx_documents_metadata_source_file"))
            .withFailMessage("Index idx_documents_metadata_source_file should exist — it serves the"
                + " per-page Confluence version-skip lookup on every crawled page")
            .isTrue();
        assertThat(indexExists("documents", "idx_documents_id_nodash"))
            .withFailMessage("Index idx_documents_id_nodash should exist (citation prefix lookup)")
            .isTrue();
        assertThat(indexExists("documents", "idx_documents_title_trgm"))
            .withFailMessage("Trigram index idx_documents_title_trgm should exist (fuzzy title)")
            .isTrue();

        assertThat(indexExists("chunks", "idx_chunks_document_id_kg_ok"))
            .withFailMessage("Index idx_chunks_document_id_kg_ok should exist (kg-status subquery)")
            .isTrue();
        assertThat(indexExists("chunks", "idx_chunks_id_nodash"))
            .withFailMessage("Index idx_chunks_id_nodash should exist (citation prefix lookup)")
            .isTrue();
    }

    /**
     * V2 columns. The migration SQL is baked into the db image, so {@code docker compose up -d}
     * reuses the old one and the columns silently never appear — this is the cheapest guard against
     * a migration that looks applied but is not.
     */
    @Test
    public void testAgentStepFailureColumnsExist() {
        assertThat(columnExists("agent_steps", "status"))
            .withFailMessage("Column agent_steps.status should exist (V2)").isTrue();
        assertThat(columnExists("agent_steps", "error"))
            .withFailMessage("Column agent_steps.error should exist (V2)").isTrue();

        String defaultExpr = jdbcTemplate.queryForObject(
            "SELECT column_default FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = 'agent_steps' AND column_name = 'status'",
            String.class);
        assertThat(defaultExpr).as("existing rows must keep meaning without a backfill")
            .contains("ok");
    }

    /**
     * V3 columns. Same guard as above, and it matters more here: these three carry money.
     * {@code LlmCallLogRepository.insert} names all three, so a stale db image would not silently
     * degrade — every LLM call would fail its insert and be swallowed by the listener's catch,
     * losing the whole cost ledger while the app looked healthy.
     */
    @Test
    public void testGatewayCacheAccountingColumnsExist() {
        assertThat(columnExists("llm_call_logs", "gateway_cache_status"))
            .withFailMessage("Column llm_call_logs.gateway_cache_status should exist (V3)")
            .isTrue();
        assertThat(columnExists("llm_call_logs", "gateway_log_id"))
            .withFailMessage("Column llm_call_logs.gateway_log_id should exist (V3)").isTrue();
        assertThat(columnExists("llm_call_logs", "cost_avoided"))
            .withFailMessage("Column llm_call_logs.cost_avoided should exist (V3)").isTrue();

        String defaultExpr = jdbcTemplate.queryForObject(
            "SELECT column_default FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = 'llm_call_logs' AND column_name = 'cost_avoided'",
            String.class);
        assertThat(defaultExpr).as("pre-V3 rows must read as 'nothing avoided', not NULL")
            .contains("0");
    }

    /**
     * V6 drops the {@code tags} registry: the tag vocabulary is derived from the {@code TEXT[]}
     * columns that actually carry it.
     *
     * <p>
     * The registry's only writer was {@code TagRepository.getOrCreateTag}, so it learned a tag only
     * when one arrived through an admin form or the ingest enqueue and missed every direct writer —
     * the corpus import above all. It held no foreign key in either direction and its {@code id}
     * was never read, so nothing depended on it while the admin dropdowns it fed silently lagged
     * the data.
     */
    @Test
    public void testTagsRegistryTableIsGone() {
        assertThat(tableExists("tags"))
            .withFailMessage("Table 'tags' must not exist: the vocabulary is derived from"
                + " collections.tags / conversations.tags (V6)")
            .isFalse();
    }

    /**
     * {@code spring.datasource.hikari.connection-init-sql} runs {@code SET statement_timeout =
     * '60s'} once per physical connection, so every statement the application issues — web,
     * background worker and MCP alike — is capped, out of one shared pool of 20.
     *
     * <p>
     * It is the only backstop against a single query pinning a connection indefinitely, and this
     * application has several ways to produce one: an HNSW/BM25 scan across the {@code chunks}
     * partitions, a cost-explorer range that touches the whole {@code llm_call_logs} table, or a
     * lock wait behind a long ingest transaction. Twenty such statements exhaust the pool, and at
     * that point nothing at all can obtain a connection — the application stops serving rather than
     * degrading, and Postgres is left holding twenty sessions that will never finish on their own.
     *
     * <p>
     * The line looks like tuning, which is exactly why it disappears: it sits among
     * {@code maximum-pool-size}/{@code idle-timeout}/{@code connection-timeout} in a block that gets
     * rewritten whenever someone tunes the pool, and removing it changes nothing observable — every
     * query this suite runs finishes in milliseconds. Asserting it here, on a connection actually
     * handed out by the pool, is what makes the setting's absence visible; asserting the YAML text
     * would only prove the string is present, not that Hikari applied it to the connections the
     * application uses.
     *
     * <p>
     * The comparison is made as an {@code interval} rather than against the text {@code SHOW}
     * returns, because Postgres normalises an interval GUC to its largest exact unit — 60s reads
     * back as {@code 1min} today, and a future value of, say, 90s would read back as {@code 90s}.
     * Pinning the rendered string would make this test fail for a reason that has nothing to do
     * with the rule. An unset timeout is {@code 0}, which is {@code 00:00:00} as an interval.
     */
    @Test
    public void pooledConnectionsCarryTheSixtySecondStatementTimeout() {
        String rendered = jdbcTemplate.queryForObject("SHOW statement_timeout", String.class);

        Boolean unbounded = jdbcTemplate.queryForObject(
            "SELECT current_setting('statement_timeout')::interval = interval '0'", Boolean.class);
        assertThat(unbounded)
            .withFailMessage("pooled connections must carry a statement_timeout, but it was '%s': "
                + "an unbounded query can hold one of the 20 pooled connections forever", rendered)
            .isFalse();

        Boolean sixtySeconds = jdbcTemplate.queryForObject(
            "SELECT current_setting('statement_timeout')::interval = interval '60 seconds'",
            Boolean.class);
        assertThat(sixtySeconds)
            .withFailMessage("statement_timeout should be 60s but was '%s'", rendered).isTrue();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            Integer.class,
            tableName
        );
        return count != null && count > 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND tablename = ? AND indexname = ?",
            Integer.class,
            tableName,
            indexName
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            Integer.class,
            tableName,
            columnName
        );
        return count != null && count > 0;
    }
}
