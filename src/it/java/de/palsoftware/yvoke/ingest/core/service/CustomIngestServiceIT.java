package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.rag.retrieval.EmbeddingService;
import de.palsoftware.yvoke.shared.jobengine.model.IngestionJob;
import de.palsoftware.yvoke.shared.jobengine.model.JobContext;
import de.palsoftware.yvoke.shared.jobengine.model.JobCounts;
import de.palsoftware.yvoke.shared.jobengine.model.JobStatus;
import de.palsoftware.yvoke.shared.jobengine.model.JobStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Characterization test for the multi-phase {@code CustomIngestService.ingest()} zip pipeline
 * (MNT-07): unzip → discover markdown → parse/chunk → embed → per-document persist → optional KG
 * injection. Establishes the end-to-end behavior (JobCounts + persisted rows) that the phase
 * extraction must preserve. Embeddings are mocked (deterministic 1024-dim zero vectors); the
 * summarizer is never invoked because the fixture markdown does not request summaries.
 *
 * <p>
 * Also pins the fail-fast contract of this path: unparseable frontmatter, an entity that resolves
 * to no document, and an unresolvable edge endpoint each abort the job with a message naming the
 * offenders — none of them may degrade silently.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration", "app.upload-dir=${java.io.tmpdir}"})
public class CustomIngestServiceIT {

    private static final String COLLECTION = "OIM-CUSTOM-INGEST-TEST";
    private static final String VERSION = "9.3";
    private static final String DOC_COUNT_SQL =
        "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?";
    private static final String ENTITY_COUNT_SQL =
        "SELECT count(*) FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?";

    @Autowired
    private CustomIngestService customIngestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private GeneralSummarizer generalSummarizer;

    @TempDir
    Path tempDir;

    private Path zipPath;

    @BeforeEach
    public void setUp() throws Exception {
        cleanup();
        // Collections are never auto-created by ingest; the target must pre-exist.
        jdbcTemplate.update(
            "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
            UUID.randomUUID(), COLLECTION);

        zipPath = writeZip("custom.zip", Map.of("docs/demo.md", markdown("table", "DEMO_TABLE"),
            "docs/other.md", markdown("table", "OTHER_TABLE"), "graph/entities.jsonl", """
                {"type":"table","name":"DEMO_TABLE","description":"the demo table"}
                {"type":"table","name":"OTHER_TABLE","description":"another table"}
                """, "graph/relationships.jsonl",
            "{\"source\":\"table:DEMO_TABLE\",\"type\":\"references\","
                + "\"target\":\"table:OTHER_TABLE\",\"description\":\"demo refs other\"}\n"));

        // Deterministic embedding: one 1024-dim zero vector per input (chunks and entity descs).
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

    /**
     * Markdown lives under a subdirectory so the default {@code **}{@code /*.md} glob matches
     * unambiguously. The frontmatter declares the kind-aware identity the graph entity links to.
     */
    private static String markdown(String kind, String name) {
        return """
            ---
            kind: %s
            name: %s
            title: %s
            ---
            # %s

            Content describing %s.
            """.formatted(kind, name, name, name, name);
    }

    private Path writeZip(String zipName, Map<String, String> entries) throws IOException {
        Path zip = tempDir.resolve(zipName);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                putEntry(zos, entry.getKey(), entry.getValue());
            }
        }
        return zip;
    }

    private static void putEntry(ZipOutputStream zos, String name, String content)
        throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private IngestionJob job(Map<String, Object> settings) {
        return job(zipPath, settings);
    }

    private IngestionJob job(Path zip, Map<String, Object> settings) {
        return new IngestionJob(UUID.randomUUID(), IngestJobKind.CUSTOM.getValue(), zip.toString(),
            List.of(VERSION), UUID.randomUUID(), COLLECTION, JobStatus.RUNNING, JobStep.CHUNK, 0, 1,
            null, null, OffsetDateTime.now(), OffsetDateTime.now(), null, settings);
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

    @Test
    public void ingestPersistsDocumentsChunksAndGraphFromZip() {
        IngestionJob job = job(Map.of());
        JobCounts counts = customIngestService.ingest(job, ctxFor(job));

        assertThat(counts.docs()).isEqualTo(2);
        assertThat(counts.chunks()).isEqualTo(2);
        assertThat(counts.entities()).isEqualTo(2);
        assertThat(counts.edges()).isEqualTo(1);

        assertThat(count(DOC_COUNT_SQL)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM chunks ch JOIN collections c ON ch.collection_id = c.id WHERE c.name = ?"))
            .isEqualTo(2);
        assertThat(count(ENTITY_COUNT_SQL)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?"))
            .isEqualTo(1);

        List<String> statuses = jdbcTemplate.queryForList(
            "SELECT d.ingestion_status FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(statuses).containsOnly("completed");

        // The invariant the whole fail-fast contract exists for: every entity carries its document.
        List<String> documentIds = jdbcTemplate.queryForList(
            "SELECT e.metadata->>'document_id' FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?",
            String.class, COLLECTION);
        assertThat(documentIds).hasSize(2).doesNotContainNull();
    }

    @Test
    public void ingestWithGraphDisabledPersistsDocsButSkipsGraph() {
        IngestionJob job = job(Map.of("enableGraph", false));
        JobCounts counts = customIngestService.ingest(job, ctxFor(job));

        assertThat(counts.docs()).isEqualTo(2);
        assertThat(counts.chunks()).isEqualTo(2);
        assertThat(counts.entities()).isZero();
        assertThat(counts.edges()).isZero();

        assertThat(count(DOC_COUNT_SQL)).isEqualTo(2);
        assertThat(count(ENTITY_COUNT_SQL)).isZero();
    }

    /**
     * The real defect, in its real shape: {@code display_name: %Globals...%} starts a plain YAML
     * scalar with the reserved {@code %} indicator, so the whole frontmatter block fails to parse.
     * The document used to fall back to {@code kind='other'} — which then matched no {@code table}
     * entity, leaving the entity with no {@code document_id} across two full re-ingests.
     */
    @Test
    public void unparseableFrontmatterFailsTheJobNamingTheFileAndNeverLandsAsKindOther()
        throws IOException {
        String broken = """
            ---
            kind: table
            name: AERole
            display_name: %Globals.QIM_ProductNameShort% application roles
            ---
            # AERole

            The application roles table.
            """;
        Path zip = writeZip("broken-frontmatter.zip",
            Map.of("docs/aerole.md", broken, "docs/demo.md", markdown("table", "DEMO_TABLE"),
                "graph/entities.jsonl",
                "{\"type\":\"table\",\"name\":\"AERole\",\"description\":\"roles\"}\n"
                    + "{\"type\":\"table\",\"name\":\"DEMO_TABLE\",\"description\":\"demo\"}\n",
                "graph/relationships.jsonl", ""));
        IngestionJob job = job(zip, Map.of());

        assertThatThrownBy(() -> customIngestService.ingest(job, ctxFor(job)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unparseable YAML frontmatter")
            .hasMessageContaining("docs/aerole.md");

        // Nothing was persisted at all — in particular no kind='other' stand-in for the broken doc.
        assertThat(count(DOC_COUNT_SQL)).isZero();
        assertThat(count(
            "SELECT count(*) FROM documents d JOIN collections c ON d.collection_id = c.id WHERE c.name = ? AND d.kind = 'other'"))
                .isZero();
        assertThat(count(ENTITY_COUNT_SQL)).isZero();
    }

    /** Every entity must be backed by a document of the same (kind, name) — no exception. */
    @Test
    public void entityWithoutAMatchingDocumentFailsTheJobNamingTheEntity() throws IOException {
        Path zip = writeZip("orphan-entity.zip",
            Map.of("docs/demo.md", markdown("table", "DEMO_TABLE"), "graph/entities.jsonl",
                "{\"type\":\"table\",\"name\":\"DEMO_TABLE\",\"description\":\"demo\"}\n"
                    + "{\"type\":\"table\",\"name\":\"QERVPersonAndAERoles\",\"description\":\"orphan\"}\n",
                "graph/relationships.jsonl", ""));
        IngestionJob job = job(zip, Map.of());

        assertThatThrownBy(() -> customIngestService.ingest(job, ctxFor(job)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1 graph entit(ies) resolved to no document")
            .hasMessageContaining("table:QERVPersonAndAERoles");

        assertThat(count(ENTITY_COUNT_SQL)).isZero();
    }

    /** An edge endpoint no entity carries is a dropped link; the job must stop and say so. */
    @Test
    public void unknownEdgeEndpointFailsTheJob() throws IOException {
        Path zip = writeZip("unknown-endpoint.zip",
            Map.of("docs/demo.md", markdown("table", "DEMO_TABLE"), "graph/entities.jsonl",
                "{\"type\":\"table\",\"name\":\"DEMO_TABLE\",\"description\":\"demo\"}\n",
                "graph/relationships.jsonl",
                "{\"source\":\"table:DEMO_TABLE\",\"type\":\"references\",\"target\":\"table:GHOST\"}\n"));
        IngestionJob job = job(zip, Map.of());

        assertThatThrownBy(() -> customIngestService.ingest(job, ctxFor(job)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown endpoint")
            .hasMessageContaining("table:GHOST");
    }

    /** A bare endpoint name carried by several kinds cannot pick a homonym; it must fail loudly. */
    @Test
    public void ambiguousBareEdgeEndpointFailsTheJob() throws IOException {
        Path zip = writeZip("ambiguous-endpoint.zip", Map.of("docs/person-table.md",
            markdown("table", "Person"), "docs/person-notification.md",
            markdown("notification", "Person"), "docs/demo.md", markdown("table", "DEMO_TABLE"),
            "graph/entities.jsonl",
            "{\"type\":\"table\",\"name\":\"Person\",\"description\":\"person table\"}\n"
                + "{\"type\":\"notification\",\"name\":\"Person\",\"description\":\"person notification\"}\n"
                + "{\"type\":\"table\",\"name\":\"DEMO_TABLE\",\"description\":\"demo\"}\n",
            "graph/relationships.jsonl",
            "{\"source\":\"Person\",\"type\":\"references\",\"target\":\"table:DEMO_TABLE\"}\n"));
        IngestionJob job = job(zip, Map.of());

        assertThatThrownBy(() -> customIngestService.ingest(job, ctxFor(job)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Ambiguous endpoint")
            .hasMessageContaining("Person (kinds: notification, table)");
    }

    private int count(String sql) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, COLLECTION);
        return n == null ? 0 : n;
    }
}
