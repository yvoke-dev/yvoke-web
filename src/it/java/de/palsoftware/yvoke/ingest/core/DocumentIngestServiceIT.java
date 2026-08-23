package de.palsoftware.yvoke.ingest.core;
import de.palsoftware.yvoke.ingest.core.service.IngestPrompts;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.ingest.core.service.DocumentIngestService;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedEntity;
import de.palsoftware.yvoke.kg.core.model.KgExtractionResult.ExtractedRelationship;
import de.palsoftware.yvoke.kg.core.service.DocumentKgExtractor;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=filesystem:docker/db/migration",
                // The UploadPathGuard confines file-backed sourceRefs to app.upload-dir;
                // @TempDir fixtures
                // live under the JVM temp dir, so widen the permitted root to it for this test.
                "app.upload-dir=${java.io.tmpdir}"
})
public class DocumentIngestServiceIT {

        private static final String COLLECTION = "OIM-INGEST-TEST";
        private static final String VERSION = "9.3";

        private static final String MARKDOWN = """
                        # OIM Authentication Guide

                        ## Introduction
                        This manual explains authentication in One Identity Manager.

                        ## Authentication Modules
                        ### OAuth
                        OAuth is a token-based authentication module.

                        ### SAML
                        SAML provides federated single sign-on.
                        """;

        @Autowired
        private DocumentIngestService documentIngestService;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @MockitoBean
        private EmbeddingService embeddingService;

        @MockitoBean
        private DocumentKgExtractor kgExtractor;

        @TempDir
        Path tempDir;

        private Path manualPath;

        @BeforeEach
        public void setUp() throws Exception {
                cleanup();
                // Collections are no longer auto-created by ingest; the target must pre-exist.
                jdbcTemplate.update(
                                "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
                                UUID.randomUUID(), COLLECTION);
                // The KG prompt is a required job setting now, so the row the jobs name must exist.
                jdbcTemplate.update("INSERT INTO system_prompts (name, type, system_prompt)"
                                + " VALUES (?, ?, ?) ON CONFLICT (name) DO NOTHING",
                                "it-kg", "KG", "Return STRICT JSON only.");
                manualPath = tempDir.resolve("auth_guide.md");
                Files.writeString(manualPath, MARKDOWN, StandardCharsets.UTF_8);

                // Deterministic embedding: one 1024-dim zero vector per input chunk.
                when(embeddingService.embedBatch(anyList())).thenAnswer(inv -> {
                        List<String> texts = inv.getArgument(0);
                        List<float[]> out = new ArrayList<>(texts.size());
                        for (int i = 0; i < texts.size(); i++) {
                                out.add(new float[1024]);
                        }
                        return out;
                });
        }

        @AfterEach
        public void tearDown() {
                cleanup();
        }

        private void cleanup() {
                jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
        }

        private IngestionJob job() {
                return new IngestionJob(
                                UUID.randomUUID(), IngestJobKind.STANDARD.getValue(), manualPath.toString(), VERSION,
                                COLLECTION,
                                JobStatus.RUNNING, JobStep.CHUNK, 0, 1, null, null,
                                OffsetDateTime.now(), OffsetDateTime.now(), null);
        }

        private JobContext ctxFor(IngestionJob job) {
                return new JobContext() {
                        @Override
                        public IngestionJob job() {
                                return job;
                        }

                        @Override
                        public void report(JobStep step, int progress) {
                                // no-op for the test
                        }
                };
        }

        /**
         * A re-ingest replaces the chunk set, so any section summary that survives it describes
         * content that no longer exists.
         *
         * <p>
         * The document row is REUSED on re-ingest — {@code upsertManualDocument} matches on
         * collection + kind + exact tag set + (source_file OR title) — and only its chunks were
         * deleted, so summaries written by an earlier run stayed attached under the same
         * {@code document_id}. {@code TocService} then joins them back onto the NEW chunks by
         * normalised heading path and serves the previous revision's prose through {@code get_toc},
         * while the admin detail page renders it as current. Nothing anywhere records that the
         * summaries predate the text.
         *
         * <p>
         * Deleting them costs nothing recoverable: {@code GeneralSummarizer} keys
         * {@code summary_cache} on {@code sha256} of the section body and that table is never
         * pruned, so re-generating an unchanged section is a cache hit and a changed one had to be
         * re-summarised regardless.
         */
        @Test
        public void aReIngestDoesNotLeaveSummariesDescribingDeletedChunks() {
                documentIngestService.ingest(job(), ctxFor(job()));

                UUID documentId = jdbcTemplate.queryForObject(
                                "SELECT d.id FROM documents d JOIN collections c ON c.id = d.collection_id "
                                                + "WHERE c.name = ?",
                                UUID.class, COLLECTION);
                jdbcTemplate.update(
                                "INSERT INTO section_summaries (id, document_id, heading_path, summary) "
                                                + "VALUES (?, ?, ARRAY[?]::text[], ?)",
                                UUID.randomUUID(), documentId, "Introduction",
                                "Summary of the PREVIOUS revision of this manual.");

                // Re-ingest without asking for summaries - the ordinary path, since the checkbox is
                // unchecked on every page load and an absent setting means off.
                documentIngestService.ingest(job(), ctxFor(job()));

                Integer left = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM section_summaries WHERE document_id = ?", Integer.class,
                                documentId);
                assertThat(left)
                                .as("a summary that outlives the chunks it describes is served by get_toc and "
                                                + "rendered on the admin page as if it were current")
                                .isZero();
        }

        @Test
        public void ingestPersistsChunksWithContiguousSortOrderAndGraph() {
                when(kgExtractor.extract(anyList(), any(), any(), any())).thenReturn(new KgExtractionResult(
                                List.of(new ExtractedEntity("OAuth", "module", "token auth"),
                                                new ExtractedEntity("SAML", "module", "sso"),
                                                new ExtractedEntity("OIM", "product", "the product")),
                                List.of(new ExtractedRelationship("OAuth", "part_of", "OIM", "")),
                                0));

                IngestionJob job = job();
                JobCounts ingestCounts = documentIngestService.ingest(job, ctxFor(job));

                assertThat(ingestCounts.docs()).isEqualTo(1);
                assertThat(ingestCounts.chunks()).isPositive();
                assertThat(ingestCounts.entities()).isZero();
                assertThat(ingestCounts.edges()).isZero();

                UUID documentId = jdbcTemplate.queryForObject(
                                "SELECT d.id FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
                                UUID.class, COLLECTION);
                IngestionJob kgJob = new IngestionJob(
                                UUID.randomUUID(), IngestJobKind.KG_EXTRACT.getValue(), documentId.toString(),
                                List.of(VERSION), UUID.randomUUID(), COLLECTION,
                                JobStatus.RUNNING, JobStep.CHUNK, 0, 1, null, null,
                                OffsetDateTime.now(), OffsetDateTime.now(), null,
                                Map.of(IngestPrompts.SETTING_KG_PROMPT, "it-kg"));
                JobCounts kgCounts = documentIngestService.processDocumentKg(kgJob, ctxFor(kgJob));

                // The three declared entities; endpoints are never materialized implicitly.
                assertThat(kgCounts.entities()).isEqualTo(3);
                assertThat(kgCounts.edges()).isEqualTo(1);

                // chunk count in DB matches reported count
                Integer dbChunks = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM chunks ch JOIN collections c ON ch.collection_id = c.id WHERE c.name = ?", Integer.class, COLLECTION);
                assertThat(dbChunks).isEqualTo(ingestCounts.chunks());

                // sort_order is contiguous 0..N-1 in document order
                List<Integer> sortOrders = jdbcTemplate.queryForList(
                                "SELECT ch.sort_order FROM chunks ch JOIN collections c ON ch.collection_id = c.id WHERE c.name = ? ORDER BY ch.sort_order",
                                Integer.class, COLLECTION);
                for (int i = 0; i < sortOrders.size(); i++) {
                        assertThat(sortOrders.get(i)).isEqualTo(i);
                }

                // document marked completed
                String status = jdbcTemplate.queryForObject(
                                "SELECT d.ingestion_status FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
                                String.class, COLLECTION);
                assertThat(status).isEqualTo("completed");

                // graph rows persisted
                Integer entityCount = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?",
                                Integer.class, COLLECTION);
                Integer edgeCount = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?",
                                Integer.class, COLLECTION);
                assertThat(entityCount).isEqualTo(3);
                assertThat(edgeCount).isEqualTo(1);
        }

        @Test
        public void reingestDoesNotDuplicateChunksOrGraph() {
                when(kgExtractor.extract(anyList(), any(), any(), any())).thenReturn(new KgExtractionResult(
                                List.of(new ExtractedEntity("OAuth", "module", "token auth"),
                                                new ExtractedEntity("OIM", "product", "the product")),
                                List.of(new ExtractedRelationship("OAuth", "part_of", "OIM", "")),
                                0));

                IngestionJob first = job();
                JobCounts firstCounts = documentIngestService.ingest(first, ctxFor(first));
                UUID docId1 = jdbcTemplate.queryForObject(
                                "SELECT d.id FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
                                UUID.class, COLLECTION);
                IngestionJob firstKg = new IngestionJob(
                                UUID.randomUUID(), IngestJobKind.KG_EXTRACT.getValue(), docId1.toString(),
                                List.of(VERSION), UUID.randomUUID(), COLLECTION,
                                JobStatus.RUNNING, JobStep.CHUNK, 0, 1, null, null,
                                OffsetDateTime.now(), OffsetDateTime.now(), null,
                                Map.of(IngestPrompts.SETTING_KG_PROMPT, "it-kg"));
                documentIngestService.processDocumentKg(firstKg, ctxFor(firstKg));

                IngestionJob second = job();
                JobCounts secondCounts = documentIngestService.ingest(second, ctxFor(second));
                UUID docId2 = jdbcTemplate.queryForObject(
                                "SELECT d.id FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
                                UUID.class, COLLECTION);
                assertThat(docId2).isEqualTo(docId1);

                IngestionJob secondKg = new IngestionJob(
                                UUID.randomUUID(), IngestJobKind.KG_EXTRACT.getValue(), docId2.toString(),
                                List.of(VERSION), UUID.randomUUID(), COLLECTION,
                                JobStatus.RUNNING, JobStep.CHUNK, 0, 1, null, null,
                                OffsetDateTime.now(), OffsetDateTime.now(), null,
                                Map.of(IngestPrompts.SETTING_KG_PROMPT, "it-kg"));
                documentIngestService.processDocumentKg(secondKg, ctxFor(secondKg));

                assertThat(secondCounts.chunks()).isEqualTo(firstCounts.chunks());

                Integer docCount = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
                                Integer.class, COLLECTION);
                Integer chunkCount = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM chunks ch JOIN collections c ON ch.collection_id = c.id WHERE c.name = ?", Integer.class, COLLECTION);
                Integer entityCount = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?",
                                Integer.class, COLLECTION);
                Integer edgeCount = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?",
                                Integer.class, COLLECTION);

                assertThat(docCount).isEqualTo(1);
                assertThat(chunkCount).isEqualTo(firstCounts.chunks());
                // OAuth + OIM, unchanged across re-ingest
                assertThat(entityCount).isEqualTo(2);
                assertThat(edgeCount).isEqualTo(1);
        }

        /**
         * A relationship endpoint the extractor never declared as an entity used to be materialized
         * as a kind-less placeholder node, splitting one logical entity into a kinded row plus a
         * kind-NULL row the kind-aware consolidator then refused to merge. The edge is dropped
         * instead — and the job still completes, but the drop is REPORTED in the job counts (this
         * path cannot throw on unpredictable model output the way custom ingest does).
         */
        @Test
        public void undeclaredRelationshipEndpointCreatesNoNodeAndDropsTheEdge() {
                when(kgExtractor.extract(anyList(), any(), any(), any())).thenReturn(new KgExtractionResult(
                                List.of(new ExtractedEntity("OAuth", "module", "token auth")),
                                List.of(new ExtractedRelationship("OAuth", "part_of", "OIM", "")),
                                0));

                IngestionJob job = job();
                documentIngestService.ingest(job, ctxFor(job));
                UUID documentId = jdbcTemplate.queryForObject(
                                "SELECT d.id FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
                                UUID.class, COLLECTION);
                IngestionJob kgJob = new IngestionJob(
                                UUID.randomUUID(), IngestJobKind.KG_EXTRACT.getValue(), documentId.toString(),
                                List.of(VERSION), UUID.randomUUID(), COLLECTION,
                                JobStatus.RUNNING, JobStep.CHUNK, 0, 1, null, null,
                                OffsetDateTime.now(), OffsetDateTime.now(), null,
                                Map.of(IngestPrompts.SETTING_KG_PROMPT, "it-kg"));

                JobCounts kgCounts = documentIngestService.processDocumentKg(kgJob, ctxFor(kgJob));

                assertThat(kgCounts.entities()).isEqualTo(1);
                assertThat(kgCounts.edges()).isZero();
                // The loss is carried out of the handler instead of dying in a log line.
                assertThat(kgCounts.skippedEdges()).isEqualTo(1);
                assertThat(kgCounts.skippedEntities()).isZero();

                List<String> names = jdbcTemplate.queryForList(
                                "SELECT e.name FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?",
                                String.class, COLLECTION);
                assertThat(names).containsExactly("OAuth");
                Integer edgeCount = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?",
                                Integer.class, COLLECTION);
                assertThat(edgeCount).isZero();
        }

        @Test
        public void zeroEntityExtractionStillPersistsChunksButReportsNoEntities() {
                when(kgExtractor.extract(anyList(), any(), any(), any())).thenReturn(
                                new KgExtractionResult(List.of(), List.of(), 0));

                IngestionJob job = job();
                JobCounts ingestCounts = documentIngestService.ingest(job, ctxFor(job));

                assertThat(ingestCounts.chunks()).isPositive();
                assertThat(ingestCounts.entities()).isZero();
                assertThat(ingestCounts.edges()).isZero();

                UUID documentId = jdbcTemplate.queryForObject(
                                "SELECT d.id FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
                                UUID.class, COLLECTION);
                IngestionJob kgJob = new IngestionJob(
                                UUID.randomUUID(), IngestJobKind.KG_EXTRACT.getValue(), documentId.toString(),
                                List.of(VERSION), UUID.randomUUID(), COLLECTION,
                                JobStatus.RUNNING, JobStep.CHUNK, 0, 1, null, null,
                                OffsetDateTime.now(), OffsetDateTime.now(), null,
                                Map.of(IngestPrompts.SETTING_KG_PROMPT, "it-kg"));
                JobCounts kgCounts = documentIngestService.processDocumentKg(kgJob, ctxFor(kgJob));

                assertThat(kgCounts.entities()).isZero();
                assertThat(kgCounts.edges()).isZero();
        }
}
