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
        "agent_steps"
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
